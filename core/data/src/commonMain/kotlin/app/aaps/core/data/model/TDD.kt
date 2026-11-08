package app.aaps.core.data.model

import app.aaps.core.data.time.systemUtcOffsetAt

data class TDD(
    override var id: Long = 0,
    override var version: Int = 0,
    override var dateCreated: Long = -1,
    override var isValid: Boolean = true,
    override var referenceId: Long? = null,
    override var ids: IDs = IDs(),
    override var timestamp: Long,
    var utcOffset: Long = systemUtcOffsetAt(timestamp),
    var basalAmount: Double = 0.0,
    var bolusAmount: Double = 0.0,
    var totalAmount: Double = 0.0, // if zero it's calculated as basalAmount + bolusAmount
    var carbs: Double = 0.0,
    var carbInsulin: Double = 0.0 // estimated insulin for carbs = sum(carbs / IC at carb time)
) : HasIDs, TimeStamped {

    /**
     * Insulin-to-carb ratio derived from carbs and bolus alone (g per 1U),
     * null if it cannot be calculated (no carbs or no bolus recorded).
     */
    val icRatio: Double?
        get() = if (carbs > 0 && bolusAmount > 0) carbs / bolusAmount else null

    /** Stored total, or the sum of basal and bolus when it was not recorded. */
    val total: Double
        get() = if (totalAmount > 0) totalAmount else basalAmount + bolusAmount

    /** Basal share of [total] in percent; 0 when nothing was recorded. */
    val basalPercent: Double
        get() = if (total > 0) basalAmount / total * 100 else 0.0

    companion object
}