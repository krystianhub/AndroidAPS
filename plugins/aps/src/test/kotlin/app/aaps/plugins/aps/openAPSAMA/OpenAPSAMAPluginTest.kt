package app.aaps.plugins.aps.openAPSAMA

import androidx.preference.PreferenceCategory
import app.aaps.core.interfaces.constraints.ConstraintsChecker
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.iob.GlucoseStatusProvider
import app.aaps.core.interfaces.pump.Pump
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.DoubleKey
import app.aaps.core.keys.IntKey
import app.aaps.plugins.aps.openAPSSMB.GlucoseStatusCalculatorSMB
import app.aaps.shared.tests.TestBaseWithProfile
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mock
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class OpenAPSAMAPluginTest : TestBaseWithProfile() {

    @Mock lateinit var constraintChecker: ConstraintsChecker
    @Mock lateinit var persistenceLayer: PersistenceLayer
    @Mock lateinit var glucoseStatusProvider: GlucoseStatusProvider
    @Mock lateinit var determineBasalAMA: DetermineBasalAMA
    private lateinit var openAPSAMAPlugin: OpenAPSAMAPlugin

    @BeforeEach fun prepare() {
        openAPSAMAPlugin = OpenAPSAMAPlugin(
            aapsLogger, rxBus, constraintChecker, rh, config, profileFunction, activePlugin,
            iobCobCalculator, processedTbrEbData, hardLimits, dateUtil, persistenceLayer, glucoseStatusProvider, preferences, determineBasalAMA,
            GlucoseStatusCalculatorSMB(aapsLogger, iobCobCalculator, dateUtil, decimalFormatter, deltaCalculator), apsResultProvider
        )
    }

    private fun useMdiPump(isMdi: Boolean) {
        val pump = mock<Pump>()
        whenever(pump.isMDI()).thenReturn(isMdi)
        whenever(pump.pumpDescription).thenReturn(testPumpPlugin.pumpDescription)
        whenever(activePlugin.activePump).thenReturn(pump)
    }

    @Test
    fun specialEnableConditionTest() {
        assertThat(openAPSAMAPlugin.specialEnableCondition()).isTrue()
    }

    @Test
    fun specialShowInListConditionTest() {
        assertThat(openAPSAMAPlugin.specialShowInListCondition()).isTrue()
    }

    @Test
    fun `special conditions follow the temp basal capability for non-mdi pumps`() {
        useMdiPump(isMdi = false)
        testPumpPlugin.pumpDescription.isTempBasalCapable = false
        assertThat(openAPSAMAPlugin.specialEnableCondition()).isFalse()
        assertThat(openAPSAMAPlugin.specialShowInListCondition()).isFalse()
        testPumpPlugin.pumpDescription.isTempBasalCapable = true
        assertThat(openAPSAMAPlugin.specialEnableCondition()).isTrue()
        assertThat(openAPSAMAPlugin.specialShowInListCondition()).isTrue()
    }

    @Test
    fun `special conditions stay enabled for mdi without temp basal capability`() {
        useMdiPump(isMdi = true)
        testPumpPlugin.pumpDescription.isTempBasalCapable = false
        assertThat(openAPSAMAPlugin.specialEnableCondition()).isTrue()
        assertThat(openAPSAMAPlugin.specialShowInListCondition()).isTrue()
    }

    @Test
    fun preferenceScreenTest() {
        val screen = preferenceManager.createPreferenceScreen(context)
        openAPSAMAPlugin.addPreferenceScreen(preferenceManager, screen, context, null)
        assertThat(screen.preferenceCount).isGreaterThan(0)
    }

    @Test
    fun `mdi preference screen exposes the pen suggestion settings`() {
        useMdiPump(isMdi = true)
        val screen = preferenceManager.createPreferenceScreen(context)
        openAPSAMAPlugin.addPreferenceScreen(preferenceManager, screen, context, null)
        // with the MDI pump the pen suggestion settings must be reachable from the AMA screen
        val category = screen.getPreference(0) as PreferenceCategory
        val keys = (0 until category.preferenceCount).mapNotNull { category.getPreference(it).key }
        assertThat(keys).containsAtLeast(
            BooleanKey.MdiBolusSuggestionEnabled.key,
            DoubleKey.MdiMinBolusSuggestion.key,
            DoubleKey.MdiMaxBolusSuggestion.key,
            BooleanKey.MdiBolusSuggestionSmoothing.key,
            IntKey.MdiBolusSuggestionMedianMinutes.key
        )
    }
}
