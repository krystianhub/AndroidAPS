package app.aaps.plugins.aps.autotune

import app.aaps.core.data.pump.defs.PumpType
import app.aaps.core.data.time.T
import app.aaps.core.interfaces.aps.Loop
import app.aaps.core.interfaces.logging.UserEntryLogger
import app.aaps.core.objects.profile.ProfileSealed
import app.aaps.core.interfaces.pump.Pump
import app.aaps.core.validators.preferences.AdaptiveIntPreference
import app.aaps.core.validators.preferences.AdaptiveSwitchPreference
import app.aaps.plugins.aps.autotune.data.ATProfile
import app.aaps.plugins.aps.autotune.data.LocalInsulin
import app.aaps.shared.tests.TestBaseWithProfile
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mock
import org.mockito.kotlin.whenever
import javax.inject.Provider

class AutotunePluginTest : TestBaseWithProfile() {

    @Mock lateinit var autotuneFS: AutotuneFS
    @Mock lateinit var autotuneIob: AutotuneIob
    @Mock lateinit var autotunePrep: AutotunePrep
    @Mock lateinit var autotuneCore: AutotuneCore
    @Mock lateinit var uel: UserEntryLogger
    @Mock lateinit var loop: Loop
    @Mock lateinit var mdiPump: Pump
    private lateinit var autotunePlugin: AutotunePlugin

    init {
        addInjector {
            if (it is AdaptiveIntPreference) {
                it.profileUtil = profileUtil
                it.preferences = preferences
                it.config = config
            }
            if (it is AdaptiveSwitchPreference) {
                it.preferences = preferences
                it.config = config
            }
        }
    }

    @BeforeEach fun prepare() {
        val atProfileProvider = Provider {
            ATProfile(activePlugin, preferences, profileUtil, dateUtil, rh, profileStoreProvider, aapsLogger)
        }
        autotunePlugin = AutotunePlugin(
            aapsLogger, rh, preferences, rxBus, profileFunction, dateUtil, activePlugin,
            autotuneFS, autotuneIob, autotunePrep, autotuneCore, config, uel, profileStoreProvider, atProfileProvider
        )
    }

    @Test
    fun preferenceScreenTest() {
        val screen = preferenceManager.createPreferenceScreen(context)
        autotunePlugin.addPreferenceScreen(preferenceManager, screen, context, null)
        assertThat(screen.preferenceCount).isGreaterThan(0)
    }

    @Test
    fun mdiProfileApplicationKeepsInputBasalForNormalAndCircadianProfiles() {
        whenever(activePlugin.activePump).thenReturn(mdiPump)
        whenever(mdiPump.model()).thenReturn(PumpType.MDI)
        whenever(mdiPump.isMDI()).thenReturn(true)

        autotunePlugin.pumpProfile = ATProfile(activePlugin, preferences, profileUtil, dateUtil, rh, profileStoreProvider, aapsLogger)
            .with(validProfile, LocalInsulin("test"))
        val tunedProfile = ATProfile(activePlugin, preferences, profileUtil, dateUtil, rh, profileStoreProvider, aapsLogger)
            .with(validProfile, LocalInsulin("test"))
        tunedProfile.basal = DoubleArray(24) { 2.0 }
        tunedProfile.updateProfile()

        val applied = autotunePlugin.profileForApplication(tunedProfile, circadian = false)!!
        val appliedCircadian = autotunePlugin.profileForApplication(tunedProfile, circadian = true)!!
        val proposed = tunedProfile.getProfile()

        for (hour in 0..23) {
            assertThat(tunedProfile.basal[hour]).isEqualTo(2.0)
            assertThat(ProfileSealed.Pure(applied, null).getBasalTimeFromMidnight(T.hours(hour.toLong()).secs().toInt())).isEqualTo(1.0)
            assertThat(ProfileSealed.Pure(appliedCircadian, null).getBasalTimeFromMidnight(T.hours(hour.toLong()).secs().toInt())).isEqualTo(1.0)
            assertThat(ProfileSealed.Pure(proposed, null).getBasalTimeFromMidnight(T.hours(hour.toLong()).secs().toInt())).isEqualTo(2.0)
        }
    }
}
