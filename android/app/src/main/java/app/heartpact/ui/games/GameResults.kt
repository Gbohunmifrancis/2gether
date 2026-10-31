package app.heartpact.ui.games

import app.heartpact.data.GameSession
import app.heartpact.data.GameStatus
import app.heartpact.data.GameType
import app.heartpact.i18n.tr
import app.heartpact.i18n.trn

/** How a finished game came out, from the phone holder's side. */
internal data class GameScore(
    val mine: Int,
    val theirs: Int,
    /** Played as a team (matching answers, drawings guessed): one shared number, out of [outOf]. */
    val together: Boolean = false,
    val outOf: Int? = null,
) {
    val won get() = !together && mine > theirs
    val lost get() = !together && theirs > mine
    val draw get() = !together && mine == theirs
}

/** The final numbers of a game, or null for games that show their own (Heart to heart) or that never finished. */
internal fun GamePlay.score(s: GameSession, me: String): GameScore? {
    if (s.status == GameStatus.Abandoned) return null
    fun versus(scores: Map<String, Int>) = GameScore(scores[me] ?: 0, scores.entries.firstOrNull { it.key != me }?.value ?: 0)
    return when (s.gameType) {
        GameType.MemoryMatch -> versus(memory(s).scores)
        GameType.ICallOn -> versus(iCallOn(s).scores)
        GameType.TruthOrDare -> versus(truthOrDare(s).scores)
        GameType.WordDash -> versus(wordDash(s).scores)
        GameType.GuessMyAnswer -> versus(guessMyAnswer(s).scores)
        GameType.Trivia -> versus(trivia(s).scores)
        GameType.TicTacToe, GameType.ConnectFour -> versus(grid(s).scores)
        GameType.WouldYouRather -> wouldYouRather(s).let { GameScore(it.scores[me] ?: 0, it.scores[me] ?: 0, together = true, outOf = it.totalRounds) }
        GameType.Handwriting -> drawAndGuess(s).let { GameScore(it.scores[me] ?: 0, it.scores[me] ?: 0, together = true, outOf = it.totalRounds) }
        GameType.CoupleQuiz -> quiz(s).let { GameScore(it.scores[me] ?: 0, it.scores[me] ?: 0, together = true, outOf = it.totalRounds.takeIf { n -> n > 0 }) }
        GameType.SpotTheDifference -> versus(spot(s).scores)
        // Snakes and ladders celebrates on its own board, once the last roll has played out.
        GameType.SkillDuel, GameType.SnakesAndLadders, GameType.Unknown -> null
    }
}

/** The picker's line about the last game: "You won Memory match, 4 to 2". */
internal fun GamePlay.lastLine(s: GameSession, me: String, partner: String): String {
    val game = title(s)
    if (s.status == GameStatus.Abandoned) return tr("{game} ended early", "game" to game)
    if (s.gameType == GameType.SnakesAndLadders) return tr("Last time, {game}: {outcome}", "game" to game, "outcome" to outcome(s, me, partner))
    // A duel is won on hearts, or on points when the hearts were level.
    if (s.gameType == GameType.SkillDuel && skillDuel(s).finished) {
        val d = skillDuel(s)
        val hearts = d.margin ?: 0
        return when (duelDecision(d)) {
            GamePlay.DuelDecision.Tie -> tr("{game} was a perfect tie", "game" to game)
            GamePlay.DuelDecision.Points -> if (d.winner == me) tr("You won {game} on points", "game" to game)
                else tr("{partner} won {game} on points", "partner" to partner, "game" to game)
            GamePlay.DuelDecision.Hearts -> if (d.winner == me) trn(hearts, "You won {game} by {count} heart", "You won {game} by {count} hearts", "game" to game)
                else trn(hearts, "{partner} won {game} by {count} heart", "{partner} won {game} by {count} hearts", "partner" to partner, "game" to game)
        }
    }
    val score = score(s, me) ?: return tr("Last game: {game}", "game" to game)
    return when {
        score.together -> tr("Last time, {game}: {outcome}", "game" to game, "outcome" to outcome(s, me, partner))
        score.won -> tr("You won {game}, {mine} to {theirs}", "game" to game, "mine" to score.mine, "theirs" to score.theirs)
        score.lost -> tr("{partner} won {game}, {theirs} to {mine}", "partner" to partner, "game" to game, "theirs" to score.theirs, "mine" to score.mine)
        else -> tr("{game} was a draw, {score} each", "game" to game, "score" to score.mine)
    }
}

/**
 * How the last game went for the phone holder, for Maya and Theo on the hub: true they won, false their partner did,
 * null for a draw, a game played as a team, or one that didn't finish.
 */
internal fun GamePlay.won(s: GameSession, me: String): Boolean? {
    if (s.status != GameStatus.Completed) return null
    fun by(winner: String?) = winner?.let { it == me }
    return when (s.gameType) {
        GameType.SkillDuel -> by(skillDuel(s).winner)
        GameType.SnakesAndLadders -> by(snakes(s).winner)
        else -> score(s, me)?.let { if (it.won) true else if (it.lost) false else null }
    }
}

/** The stamp on a finished game. Played together, it says how in step you were; never anything unkind. */
internal fun GamePlay.stamp(score: GameScore, partner: String): String {
    if (score.together) {
        val share = score.outOf?.let { score.mine / it.toFloat() } ?: 0f
        return when {
            share >= 0.8f -> tr("In sync!")
            share >= 0.5f -> tr("Well matched!")
            else -> tr("Good game!")
        }
    }
    return when {
        score.won -> tr("You won!")
        score.lost -> tr("{partner} won", "partner" to partner)
        else -> tr("A draw")
    }
}
