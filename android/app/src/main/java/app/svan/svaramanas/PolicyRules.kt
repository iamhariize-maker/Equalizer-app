package app.svan.svaramanas

import org.json.JSONArray

/** Read-only description of the compiled native policy, not a second decision engine. */
data class PolicyRule(
    val id: String, val version: Int, val owner: String, val inputs: List<String>,
    val min: Double, val max: Double, val units: String, val parameter: String,
    val confidence: Double, val maxAgeSeconds: Double, val sameEpoch: Boolean, val nativeOnly: Boolean,
    val reason: String, val competes: String, val rollback: String,
) {
    companion object {
        fun parse(json: String): List<PolicyRule> {
            val rules = JSONArray(json)
            return List(rules.length()) { i ->
                val r = rules.getJSONObject(i)
                val inputs = r.getJSONArray("inputs")
                val min = r.getDouble("min"); val max = r.getDouble("max")
                require(min.isFinite() && max.isFinite() && min <= max)
                PolicyRule(r.getString("id"), r.getInt("version"), r.getString("owner"),
                    List(inputs.length()) { j -> inputs.getJSONObject(j).let { "${it.getString("name")} [${it.getString("units")}]${if (it.getBoolean("proxy")) " (proxy)" else ""}" } },
                    min, max, r.getString("units"), r.getString("parameter"), r.getDouble("minConfidence"),
                    r.getDouble("maxAgeSeconds"), r.getBoolean("sameEpoch"), r.getBoolean("nativeOnly"),
                    r.getString("reason"), r.getString("competes"), r.getString("rollback"))
            }.also { require(it.map(PolicyRule::id).distinct().size == it.size) }
        }
    }
}
