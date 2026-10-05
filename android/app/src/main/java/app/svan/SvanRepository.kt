package app.svan

import android.content.Context
import app.svan.model.AudioSettings
import app.svan.model.EqMode
import app.svan.model.EqState
import app.svan.model.Preset
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/**
 * Single source of truth for the EQ curve and audiophile settings.
 *
 * The UI writes here; this pushes to the curve engine synchronously (cheap, so
 * the graph is always exact) and to Engine A's DynamicsProcessing on a
 * background coroutine, conflated so a fast drag never queues up binder calls.
 * CaptureService (Engine B) observes [eq] and [settings] directly.
 */
object SvanRepository {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var appContext: Context
    private lateinit var prefs: android.content.SharedPreferences

    private val _eq = MutableStateFlow(EqState())
    val eq: StateFlow<EqState> = _eq.asStateFlow()

    private val _settings = MutableStateFlow(AudioSettings())
    val settings: StateFlow<AudioSettings> = _settings.asStateFlow()

    private val _userPresets = MutableStateFlow<List<Preset>>(emptyList())
    val userPresets: StateFlow<List<Preset>> = _userPresets.asStateFlow()

    /** Bumped whenever Engine A has applied the newest curve (for UI status). */
    private val _engineARevision = MutableStateFlow(0)
    val engineARevision: StateFlow<Int> = _engineARevision.asStateFlow()

    @Volatile private var initialized = false

    fun init(context: Context) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            appContext = context.applicationContext
            prefs = appContext.getSharedPreferences("svan", Context.MODE_PRIVATE)
            prefs.getString("eq", null)?.let { s -> runCatching { _eq.value = EqState.fromJson(JSONObject(s)) } }
            prefs.getString("settings", null)?.let { s -> runCatching { _settings.value = AudioSettings.fromJson(JSONObject(s)) } }
            prefs.getString("presets", null)?.let { s ->
                runCatching {
                    val a = JSONArray(s)
                    _userPresets.value = List(a.length()) { Preset.fromJson(a.getJSONObject(it)) }
                }
            }
            val makeAutoDefault = !prefs.getBoolean("smartEqDefaultApplied", false)
            if (makeAutoDefault) {
                _eq.value = _eq.value.copy(smartEqControl=true)
                prefs.edit().putBoolean("smartEqDefaultApplied",true).apply()
            }
            applyCurve(_eq.value)
            EqController.globalEq.reconfigure(_settings.value.systemBands, _settings.value.systemFrameMs)
            initialized = true
            app.svan.svaramanas.Svaramanas.init(appContext)
            if (makeAutoDefault) app.svan.svaramanas.Svaramanas.update {
                it.copy(enabled=true, mode=app.svan.svaramanas.SmartMode.SVARESA)
            }
            scope.launch {
                // StateFlow is already conflated: a slow binder update never queues stale curves.
                kotlinx.coroutines.flow.combine(_eq, _settings) { state, settings -> state to settings }.collect { (state, _) ->
                    EqController.globalEq.setDynamics(state.bassCharacter, state.bass.crossoverHz, state.activeVocal.smoothness, state.levelling)
                    EqController.globalEq.applyCurveFrom(EqController.curveEngine, _settings.value.gainProtection, _eq.value.enabled)
                    _engineARevision.update { it + 1 }
                    prefs.edit().putString("eq", state.toJson().toString()).apply()
                }
            }
        }
    }

    // ---- EQ edits (call from the UI thread) ----

    fun update(transform: (EqState) -> EqState) {
        val old = _eq.value
        val next = transform(old)
        if (next == old) return
        applyCurve(next)
        _eq.value = next
    }

    private val _eqUndo = MutableStateFlow<List<EqState>>(emptyList())
    val eqUndo: StateFlow<List<EqState>> = _eqUndo.asStateFlow()
    /** Undo manual workspace edits without rewinding the live analysis/controller. */
    private var lastEqEditNanos = 0L
    fun editEq(transform: (EqState) -> EqState) {
        val old = _eq.value
        val next = transform(old)
        if (next == old) return
        val now=System.nanoTime()
        if (_eqUndo.value.isEmpty() || now-lastEqEditNanos > 700_000_000L)
            _eqUndo.value = (_eqUndo.value + old).takeLast(30)
        lastEqEditNanos=now
        update { next }
    }
    fun undoEq() {
        val old = _eqUndo.value.lastOrNull() ?: return
        _eqUndo.value = _eqUndo.value.dropLast(1)
        update { it.copy(mode=old.mode, bands=old.bands, graphicCount=old.graphicCount,
            graphicGains=old.graphicGains, graphicShelfEnds=old.graphicShelfEnds,
            preampDb=old.preampDb, presetName=old.presetName) }
    }
    fun setSmartEqControl(on: Boolean) {
        // The controller publishes ownership and its layer together when disabling.
        // Never publish manual + stale automatic bands during the handover.
        app.svan.svaramanas.Svaramanas.update { it.copy(enabled=on, mode=app.svan.svaramanas.SmartMode.SVARESA) }
        if (on && _eq.value.smartBypass) update { it.copy(smartBypass=false) }
    }
    /** Returns the measured fit error when converting a manual curve to graphic. */
    fun setEqMode(mode: EqMode, count: Int = _eq.value.workspaceGraphicCount): NativeEngine.Companion.Fit? {
        val s = _eq.value
        if (s.smartEqControl) {
            update { it.copy(smartEqMode=mode, smartGraphicCount=count) }
            app.svan.svaramanas.Svaramanas.refreshEq()
            return null
        }
        if (mode == s.mode && (mode == EqMode.PARAMETRIC || count == s.graphicCount)) return null
        if (mode == EqMode.PARAMETRIC) {
            editEq { it.copy(mode=mode, bands=it.manualBands(), presetName="Custom") }
            return null
        }
        val fit=NativeEngine.fitGraphic(s.manualBands(),count)
        editEq { it.copy(mode=mode, graphicCount=count, graphicShelfEnds=true,
            graphicGains=fit.bands.map { b -> b.gainDb }, presetName="Custom") }
        return fit
    }
    fun adjustSmartEq(index: Int, gain: Double) {
        val s = _eq.value
        val b = s.smart?.bands?.getOrNull(index) ?: return
        val key = if(s.smartEqMode == EqMode.GRAPHIC) "g${s.smartGraphicCount}:${EqState.smartBandKey(b)}" else EqState.smartBandKey(b)
        val offset=((s.smartEqOffsets[key] ?: 0.0)+(gain-b.gainDb)).coerceIn(-3.0,3.0)
        update { it.copy(smartEqOffsets=it.smartEqOffsets + (key to offset)) }
        app.svan.svaramanas.Svaramanas.refreshEq()
    }
    fun resetSmartEqOffset(index: Int) {
        val s = _eq.value
        val b = s.smart?.bands?.getOrNull(index) ?: return
        val key=if (s.smartEqMode == EqMode.GRAPHIC) "g${s.smartGraphicCount}:${EqState.smartBandKey(b)}" else EqState.smartBandKey(b)
        update { it.copy(smartEqOffsets=it.smartEqOffsets-key) }
        app.svan.svaramanas.Svaramanas.refreshEq()
    }
    fun resetSmartEqOffsets() {
        update { it.copy(smartEqOffsets=emptyMap()) }
        app.svan.svaramanas.Svaramanas.refreshEq()
    }

    fun applyPreset(p: Preset) {
        if (_eq.value.smartEqControl) setSmartEqControl(false)
        editEq { it.withPreset(p).copy(smartEqControl=false) }
    }

    /** Full reset: every layer, and Svaramanas goes back to resting. */
    fun resetSound() {
        _eqUndo.value = emptyList()
        app.svan.svaramanas.Svaramanas.update { it.copy(enabled = false) }
        update { EqState() }
    }

    /** Imports AutoEq / Equalizer APO text. Returns the number of bands, or 0 if nothing parsed. */
    fun importParametric(name: String, text: String): Int {
        val (preamp, bands) = NativeEngine.parseParametric(text)
        if (bands.isEmpty()) return 0
        val preset = Preset(name, preamp, bands.map {
            app.svan.model.Band(it.type, it.freqHz, it.gainDb, it.q, it.enabled)
        })
        saveUserPreset(preset)
        applyPreset(preset)
        return bands.size
    }

    fun saveUserPreset(p: Preset) {
        _userPresets.update { list -> list.filterNot { it.name == p.name } + p }
        persistPresets()
    }

    fun deleteUserPreset(name: String) {
        _userPresets.update { list -> list.filterNot { it.name == name } }
        persistPresets()
    }

    fun currentAsPreset(name: String): Preset {
        val s = _eq.value
        return if (s.smartEqControl && s.smart != null) Preset(name,s.effectivePreampDb(),s.smart.bands)
        else Preset(name, s.preampDb, s.manualBands()) // headphone/bass/stereo processors remain separate
    }

    // ---- settings ----

    fun updateSettings(transform: (AudioSettings) -> AudioSettings) {
        val old = _settings.value
        val next = transform(old)
        EqController.curveEngine.setAutoHeadroom(next.autoHeadroom)
        _settings.value = next
        prefs.edit().putString("settings", next.toJson().toString()).apply()
        if (next.engineMode == app.svan.model.EngineMode.SYSTEM_ONLY && next.engineMode != old.engineMode) {
            appContext.stopService(android.content.Intent(appContext, CaptureService::class.java))
        }
        if (next.systemBands != old.systemBands || next.systemFrameMs != old.systemFrameMs) {
            scope.launch {
                EqController.globalEq.reconfigure(next.systemBands, next.systemFrameMs)
                EqController.globalEq.applyCurveFrom(EqController.curveEngine, _settings.value.gainProtection, _eq.value.enabled)
            }
        }
    }

    // ---- internals ----

    private fun applyCurve(s: EqState) {
        val engine = EqController.curveEngine
        // The curve engine renders Engine A's curve, so it gets the system-effects stand-ins.
        engine.setAutoHeadroom(_settings.value.autoHeadroom)
        engine.setBands(s.systemEffectsBands().map { it.toNative() })
        engine.setPreampDb(s.effectivePreampDb())
    }

    private fun persistPresets() {
        prefs.edit().putString("presets", JSONArray().apply { _userPresets.value.forEach { put(it.toJson()) } }.toString()).apply()
    }
}
