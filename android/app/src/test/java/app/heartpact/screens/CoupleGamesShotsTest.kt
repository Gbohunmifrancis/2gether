package app.heartpact.screens

import app.heartpact.data.GameSession
import app.heartpact.data.GameStatus
import app.heartpact.data.GameType
import app.heartpact.ui.games.GameActions
import app.heartpact.ui.games.GamesScreen
import app.heartpact.ui.games.GamesUi
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Instant

/** Same answer at every step, and the round-by-round recap under each of the four answer games once it's over. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w390dp-h844dp-xxhdpi")
class CoupleGamesShotsTest {
    private val now = Instant.parse("2026-10-31T18:00:00Z")

    private fun session(type: GameType, state: String, status: GameStatus = GameStatus.InProgress, region: String = "global") =
        GameSession("g", "c", type, status, currentTurnUserId = if (status == GameStatus.InProgress) "maya" else null, stateVersion = 9, stateJson = state,
            invitedByUserId = "theo", endedAtUtc = if (status == GameStatus.Completed) "2026-10-31T17:58:00Z" else null, setupJson = """{"region":"$region"}""")

    private fun screen(name: String, s: GameSession, night: Boolean = false, me: String = "maya", partner: String = "Theo") =
        shot(name, night) { GamesScreen(GamesUi(listOf(s), loaded = true), me, partner, GameActions(), { now }) }

    private val matched = """{"round":3,"prompt":"What's our go-to takeaway?","kind":"text","answers":{"maya":"Thai green curry","theo":"thai green curry!"},"match":true}"""
    private val missed = """{"round":3,"prompt":"Where should our next trip be?","kind":"text","answers":{"maya":"Lisbon","theo":"Somewhere with a beach and no phone signal"},"match":false}"""

    /** Theo is in; Maya types hers. Last round matched. */
    @Test fun sameAnswer() = screen("games-same", session(GameType.CoupleQuiz,
        """{"players":["maya","theo"],"scores":{"maya":3,"theo":3},"round":4,"totalRounds":10,"prompt":"What would we name a pet?","kind":"text",
        "answerCount":1,"answered":["theo"],"mine":null,"last":$matched}"""))

    /** A "Who" question: Maya picks one of the two of them; last round's answers differed. */
    @Test fun sameAnswerWho() = screen("games-same-who", session(GameType.CoupleQuiz,
        """{"players":["maya","theo"],"scores":{"maya":3,"theo":3},"round":4,"totalRounds":10,"prompt":"Who falls asleep first?","kind":"who",
        "answerCount":0,"answered":[],"mine":null,"last":$missed}"""))

    /** Night: Maya's answer is in, waiting for Theo; last round was a "who" match. */
    @Test fun sameAnswerWaitingNight() = screen("games-same-waiting-night", session(GameType.CoupleQuiz,
        """{"players":["maya","theo"],"scores":{"maya":5,"theo":5},"round":6,"totalRounds":10,"prompt":"Which board game would we play tonight?","kind":"text",
        "answerCount":1,"answered":["maya"],"mine":"Scrabble","last":{"round":5,"prompt":"Who is the better cook?","kind":"who","answers":{"maya":"theo","theo":"theo"},"match":true}}"""), night = true)

    private val close = """{"round":4,"prompt":"What's our perfect Friday-night dinner?","kind":"text","answers":{"maya":"pizza","theo":"Pepperoni pizza"},"match":false}"""
    private fun counting(last: String) = """{"players":["maya","theo"],"scores":{"maya":4,"theo":4},"round":5,"totalRounds":10,"prompt":"Where should we go for our next weekend away?","kind":"text",
        "answerCount":0,"answered":[],"mine":null,"last":$last}"""

    /** Close, but not the same: either of them can say it counts. */
    @Test fun sameAnswerCountable() = screen("games-same-countable", session(GameType.CoupleQuiz, counting(close)))

    /** Theo said it counts: stamped, a heart each, on Maya's phone too. */
    @Test fun sameAnswerCounted() = screen("games-same-counted", session(GameType.CoupleQuiz,
        counting(close.replace(""""match":false""", """"match":true,"accepted":true,"acceptedBy":"theo""""))))

    @Test fun sameAnswerCountedNight() = screen("games-same-counted-night", session(GameType.CoupleQuiz,
        counting(close.replace(""""match":false""", """"match":true,"accepted":true,"acceptedBy":"maya""""))), night = true)

    private val countedHistory = """{"round":0,"prompt":"What's our song?","kind":"text","answers":{"maya":"Golden hour","theo":"golden hour"},"match":true},
        {"round":1,"prompt":"What's our perfect Friday-night dinner?","kind":"text","answers":{"maya":"pizza","theo":"Pepperoni pizza"},"match":true,"accepted":true,"acceptedBy":"theo"},
        {"round":2,"prompt":"Who falls asleep first?","kind":"who","answers":{"maya":"maya","theo":"theo"},"match":false},
        {"round":3,"prompt":"Where did we first meet?","kind":"text","answers":{"maya":"At Sam's party","theo":"Sam's birthday"},"match":false}"""

    /** Over: the recap still counts a round that was close, and says which ones were counted. */
    @Test fun sameAnswerFinishedCounted() = screen("games-same-finished-counted", session(GameType.CoupleQuiz,
        """{"players":["maya","theo"],"scores":{"maya":2,"theo":2},"round":4,"totalRounds":4,"prompt":null,"kind":null,"answerCount":0,"answered":[],"history":[$countedHistory]}""",
        GameStatus.Completed))

    @Test fun sameAnswerFinishedCountedNight() = screen("games-same-finished-counted-night", session(GameType.CoupleQuiz,
        """{"players":["maya","theo"],"scores":{"maya":2,"theo":2},"round":4,"totalRounds":4,"prompt":null,"kind":null,"answerCount":0,"answered":[],"history":[$countedHistory]}""",
        GameStatus.Completed), night = true)

    private val quizHistory = (0 until 10).joinToString(",") { i ->
        val prompts = listOf("What's our song?", "Who said \"I love you\" first?", "Where did we first meet?", "What's our go-to takeaway?", "Who falls asleep first?",
            "Which season suits us best?", "What one word sums us up?", "Who is the funnier one?", "Which city should we live in one day?", "What's our favourite breakfast?")
        val who = prompts[i].startsWith("Who")
        val answers = if (who) (if (i % 2 == 0) """{"maya":"theo","theo":"theo"}""" else """{"maya":"maya","theo":"theo"}""")
            else if (i % 3 == 0) """{"maya":"Pancakes","theo":"pancakes"}""" else """{"maya":"At a friend's wedding","theo":"The bus stop"}"""
        val match = if (who) i % 2 == 0 else i % 3 == 0
        """{"round":$i,"prompt":${'"'}${prompts[i].replace("\"", "\\\"")}${'"'},"kind":"${if (who) "who" else "text"}","answers":$answers,"match":$match}"""
    }

    /** Over: the shared total, then every round with both answers. */
    @Test fun sameAnswerFinished() = screen("games-same-finished", session(GameType.CoupleQuiz,
        """{"players":["maya","theo"],"scores":{"maya":6,"theo":6},"round":10,"totalRounds":10,"prompt":null,"kind":null,"answerCount":0,"answered":[],"history":[$quizHistory]}""",
        GameStatus.Completed))

    @Test fun ratherFinished() = screen("games-rather-finished", session(GameType.WouldYouRather,
        """{"players":["maya","theo"],"scores":{"maya":7,"theo":7},"round":10,"totalRounds":10,"phase":"completed","picked":[],
        "history":[{"round":0,"a":"Breakfast in bed","b":"Dinner under the stars","picks":{"maya":1,"theo":1},"match":true},
        {"round":1,"a":"A beach holiday","b":"A mountain cabin","picks":{"maya":0,"theo":1},"match":false},
        {"round":2,"a":"Have a dog","b":"Have a cat","picks":{"maya":0,"theo":0},"match":true}]}""", GameStatus.Completed), night = true)

    @Test fun guessFinished() = screen("games-guess-finished", session(GameType.GuessMyAnswer,
        """{"players":["maya","theo"],"scores":{"maya":3,"theo":2},"round":8,"totalRounds":8,"phase":"completed",
        "history":[{"round":0,"subject":"maya","guesser":"theo","question":"Your dream holiday?","choices":["Beach","Mountains","Big city","Road trip"],"answer":0,"guess":0,"right":true},
        {"round":1,"subject":"theo","guesser":"maya","question":"Your ideal Saturday?","choices":["Lie-in","Long walk","Friends over","Market run"],"answer":1,"guess":3,"right":false},
        {"round":2,"subject":"maya","guesser":"theo","question":"How do you handle a long queue?","choices":["Wait patiently","Chat to people","Leave","Sigh loudly"],"answer":3,"guess":0,"right":false}]}""",
        GameStatus.Completed))

    @Test fun triviaFinished() = screen("games-trivia-finished", session(GameType.Trivia,
        """{"players":["maya","theo"],"scores":{"maya":6,"theo":7},"round":10,"totalRounds":10,"question":null,"answered":[],"completed":true,
        "history":[{"round":0,"question":{"text":"Which planet is known as the Red Planet?","options":["Venus","Mars","Jupiter","Mercury"]},"answer":1,"picks":{"maya":1,"theo":1}},
        {"round":1,"question":{"text":"Who painted The Starry Night?","options":["Claude Monet","Pablo Picasso","Vincent van Gogh","Salvador Dalí"]},"answer":2,"picks":{"maya":0,"theo":2}},
        {"round":2,"question":{"text":"How many keys does a standard piano have?","options":["76","88","61","100"]},"answer":1,"picks":{"maya":1,"theo":3}}]}""",
        GameStatus.Completed))
}

/** Same answer on a small phone, and on a tablet on its side. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CoupleGamesSizesShotsTest {
    private val now = Instant.parse("2026-10-31T18:00:00Z")
    @androidx.compose.runtime.Composable private fun same(state: String) = GamesScreen(GamesUi(listOf(GameSession("g", "c", GameType.CoupleQuiz, GameStatus.InProgress, currentTurnUserId = "maya",
        stateVersion = 4, stateJson = state, invitedByUserId = "theo"))), "maya", "Theodore-Alexander", GameActions(), { now })

    private val who = """{"players":["maya","theo"],"scores":{"maya":1,"theo":1},"round":2,"totalRounds":10,"prompt":"Who is more likely to cry at a film?","kind":"who",
        "answerCount":1,"answered":["theo"],"mine":null,
        "last":{"round":1,"prompt":"What's our favourite way to unwind after a long day?","kind":"text","answers":{"maya":"A long bath with candles and a podcast","theo":"Sofa, blanket, and whatever is on"},"match":false}}"""

    @Config(sdk = [35], qualifiers = "w320dp-h640dp-mdpi")
    @Test fun small() = shot("games-same-small") { same(who) }

    @Config(sdk = [35], qualifiers = "w1280dp-h800dp-land-xhdpi")
    @Test fun tablet() = shot("games-same-tablet") { same(who) }
}
