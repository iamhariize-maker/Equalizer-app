package app.svan.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.unit.dp
import app.svan.SvanRepository
import app.svan.CaptureService
import app.svan.listening.*
import app.svan.svaramanas.Svaramanas
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.security.SecureRandom
import java.io.ByteArrayOutputStream
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs

@Composable
fun BlindListening() {
    val context=LocalContext.current
    val scope=rememberCoroutineScope()
    val player=remember {ClipPlayer(context)}
    val lifecycle=LocalLifecycleOwner.current.lifecycle
    DisposableEffect(player,lifecycle){
        val observer=LifecycleEventObserver {_,event->if(event==Lifecycle.Event.ON_STOP)player.stop()}
        lifecycle.addObserver(observer)
        onDispose{lifecycle.removeObserver(observer);player.close()}
    }
    var render by remember {mutableStateOf<BlindRenderer.Render?>(null)}
    var round by remember {mutableStateOf(BlindRound(SecureRandom().nextBoolean()))}
    var message by remember {mutableStateOf("Pause other players before listening. A and B contain the same excerpt; their identities stay hidden until you vote.")}
    var busy by remember {mutableStateOf(false)}
    var voted by remember {mutableStateOf(false)}
    val prefs=remember {context.getSharedPreferences("blind-listening",0)}
    var votes by remember {mutableStateOf(runCatching{JSONArray(prefs.getString("votes","[]"))}.getOrDefault(JSONArray()))}
    fun prepare(load: suspend ()->WavClip) {
        player.stop();busy=true;render=null;voted=false
        val eq=SvanRepository.eq.value;val settings=SvanRepository.settings.value;val request=Svaramanas.request.value
        scope.launch {
            runCatching {val clip=load();withContext(Dispatchers.Default){BlindRenderer.render(context,clip,eq,settings,request)}}
                .onSuccess {render=it;round=BlindRound(SecureRandom().nextBoolean());message="Ready · matched within %.2f dB. Both outputs are lowered as needed; no makeup boost.".format(abs(it.levels[2]-it.levels[3]))}
                .onFailure {message=it.message ?: "Couldn't prepare this clip"}
            busy=false
        }
    }
    val picker=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) {uri->
        if(uri!=null)prepare {withContext(Dispatchers.IO){
            val bytes=context.contentResolver.openInputStream(uri)?.use {input->
                val out=ByteArrayOutputStream();val buffer=ByteArray(16384)
                while(true){val n=input.read(buffer);if(n<0)break;require(out.size()+n<=16_777_216){"Choose a WAV smaller than 16 MB"};out.write(buffer,0,n)};out.toByteArray()
            } ?: error("Couldn't open the file")
            WavClip.decode(bytes)
        }}
    }
    fun play(choiceA: Boolean) {
        val ready=render ?: return
        scope.launch {runCatching {withContext(Dispatchers.IO){player.play(if(round.enhancedFor(choiceA))ready.enhanced else ready.original,ready.rate)}}
            .onFailure {message=it.message ?: "Couldn't play the comparison"}}
    }
    fun vote(choice: Int) {
        if(voted||render==null)return
        player.stop();voted=true
        val result=round.result(choice)
        val next=JSONArray();for(i in maxOf(0,votes.length()-49) until votes.length())next.put(votes.getJSONObject(i))
        next.put(JSONObject().put("time",System.currentTimeMillis()).put("preferredProcessing",result ?: JSONObject.NULL)
            .put("matchDb",abs(render!!.levels[2]-render!!.levels[3])).put("headphone",render!!.headphone).put("configuration",render!!.configuration))
        votes=next;prefs.edit().putString("votes",next.toString()).apply()
        message=when(result){true->"You preferred the processed version.";false->"You preferred the original version.";null->"No preference recorded."}+" This is your listening result for this excerpt."
    }
    AlertDialog(onDismissRequest={BlindLab.open.value=false},
        title={Text("Blind listening")},
        text={Column(Modifier.heightIn(max=440.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(10.dp)){
            Text("Compare the original with an audiophile-engine render of your settings, including headphone correction and Svaresa. The plan is frozen for this clip; Android system effects may differ.",style=MaterialTheme.typography.bodySmall)
            Text(message,style=MaterialTheme.typography.bodySmall,color=Svan.TextMuted)
            if(busy){CircularProgressIndicator(color=Svan.Gold);Text("Preparing the same eight-second excerpt…",style=MaterialTheme.typography.bodySmall)}
            Button(onClick={picker.launch(arrayOf("audio/wav","audio/x-wav","audio/wave"))},enabled=!busy,modifier=Modifier.fillMaxWidth()){Text("Choose WAV excerpt")}
            Button(onClick={prepare {ClipRecorder.record()}},enabled=!busy&&CaptureService.isRunning,modifier=Modifier.fillMaxWidth()){Text("Record 8 seconds of capture")}
            Text("Recording uses already-permitted capture. Blocked streams need a local WAV. Clips stay in memory and are discarded when you close; only your votes are saved on this phone.",style=MaterialTheme.typography.bodySmall,color=Svan.TextFaint)
            if(render!=null){
                Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){Button(onClick={play(true)},modifier=Modifier.weight(1f)){Text("Listen A")};Button(onClick={play(false)},modifier=Modifier.weight(1f)){Text("Listen B")}}
                TextButton(onClick={player.stop()}){Text("Stop")}
                if(!voted){Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){Button(onClick={vote(1)},modifier=Modifier.weight(1f)){Text("Prefer A")};Button(onClick={vote(2)},modifier=Modifier.weight(1f)){Text("Prefer B")}};TextButton(onClick={vote(0)}){Text("No preference")}}
                else TextButton(onClick={round=BlindRound(SecureRandom().nextBoolean());voted=false;message="New hidden assignment. Listen again before voting."}){Text("Another blind round")}
            }
            if(votes.length()>0){Text("${votes.length()} local listening result(s)",style=MaterialTheme.typography.bodySmall);TextButton(onClick={votes=JSONArray();prefs.edit().remove("votes").apply()}){Text("Clear results")}}
        }},
        confirmButton={TextButton(onClick={BlindLab.open.value=false}){Text("Close")}})
}
