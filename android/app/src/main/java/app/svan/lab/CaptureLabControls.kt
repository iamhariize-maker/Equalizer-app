package app.svan.lab

import app.svan.lab.core.Planner

/** Immutable selection per epoch. No Android effect is attached to capture's replay session. */
class CaptureLabControls(val plan: Planner.Plan, table: Planner.EqTable) {
    val rate = plan.model.rate
    val block = plan.model.block
    val stops = plan.model.stops.copyOf()
    val gains = plan.gains.copyOf()
    val inputGainDb = plan.attenuationDb
    val coefficients = table.coefficients(rate, 60, plan.eq0) + table.coefficients(rate, 230, plan.eq1)
}
