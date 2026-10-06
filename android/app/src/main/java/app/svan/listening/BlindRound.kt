package app.svan.listening

/** Assignment is hidden until the listener votes. No level-based preference inference. */
class BlindRound(private val enhancedIsA: Boolean) {
    fun enhancedFor(choiceA: Boolean)=choiceA==enhancedIsA
    fun result(choice: Int): Boolean?=when(choice){0->null;1->enhancedFor(true);2->enhancedFor(false);else->error("Invalid vote")}
}
