package cz.magicheroes.tournamentmanager.domain

import cz.magicheroes.tournamentmanager.data.MatchEntity
import kotlin.math.log2

/** Tournament entry snapshot; a player's later nickname or clan change cannot rewrite history. */
data class Entrant(
    val playerId: Long,
    val name: String,
    val clanId: Long?,
    val clanName: String?
)

/** Pure bracket plan. Target links are fixed before any result is recorded. */
object BracketEngine {
    /** A saved match result is reusable only while both player slots are unchanged. */
    fun hasSameEntrants(previous: MatchEntity, player1Id: Long?, player2Id: Long?): Boolean =
        previous.player1Id == player1Id && previous.player2Id == player2Id

    data class Blueprint(
        val key: String,
        val roundNumber: Int,
        val branch: String,
        var nextWinnerKey: String? = null,
        var winnerTargetSlot: Int? = null,
        var nextLoserKey: String? = null,
        var loserTargetSlot: Int? = null,
        var final: Boolean = false
    )

    fun createPlan(tournamentId: Long, entrants: List<Entrant>): List<MatchEntity> {
        require(entrants.size in 2..32) { "Turnaj musí mít 2 až 32 hráčů." }
        require(entrants.map { it.playerId }.distinct().size == entrants.size) {
            "Jeden hráč nemůže být v turnaji dvakrát."
        }

        // Reserve opening slots before the first result: small 8, medium 16, large 32.
        // A late participant can then fill a waiting slot without rebuilding the graph.
        val size = when {
            entrants.size <= 8 -> 8
            entrants.size <= 16 -> 16
            else -> 32
        }
        val winnersRoundCount = log2(size.toDouble()).toInt()
        val nodes = mutableListOf<Blueprint>()
        val winnersByRound = mutableMapOf<Int, List<Blueprint>>()

        for (round in 1..winnersRoundCount) {
            val count = size shr round
            val row = (0 until count).map { index ->
                Blueprint("W${round}_$index", round, "WINNERS")
            }
            winnersByRound[round] = row
            nodes += row
        }

        val losersByRound = mutableMapOf<Int, List<Blueprint>>()
        // Keep both branches the same number of rounds. The opening round in
        // each branch has the same capacity; later rounds halve until one final
        // lower-bracket match remains (e.g. 4-2-1, 8-4-2-1, 16-8-4-2-1).
        val lowerRoundCount = winnersRoundCount
        for (round in 1..lowerRoundCount) {
            val count = size shr (round + 1)
            val row = (0 until count).map { index ->
                Blueprint("L${round}_${index}", round, "LOSERS")
            }
            losersByRound[round] = row
            nodes += row
        }

        val finalNode = Blueprint("G", winnersRoundCount + 1, "FINAL", final = true)
        nodes += finalNode

        // Winners branch connections.
        for (round in 1..winnersRoundCount) {
            val row = winnersByRound.getValue(round)
            row.forEachIndexed { index, node ->
                if (round < winnersRoundCount) {
                    node.nextWinnerKey = "W${round + 1}_${index / 2}"
                    node.winnerTargetSlot = index % 2
                } else {
                    node.nextWinnerKey = "G"
                    node.winnerTargetSlot = 0
                }

                if (round == 1) {
                    node.nextLoserKey = "L1_${index / 2}"
                    node.loserTargetSlot = index % 2
                } else {
                    // Losers from every winners-branch round are routed by the repository
                    // into the first available opening slot. Keep the graph link within
                    // the opening losers round; later-round-specific links would point
                    // beyond the equal-depth bracket for smaller capacities.
                    node.nextLoserKey = "L1_$index"
                    node.loserTargetSlot = 1
                }
            }
        }

        // Connect lower-bracket winners according to the actual next-round capacity.
        // When the next round is smaller, pairs feed its two slots; when capacities
        // are equal, each winner advances into a separate waiting match.
        for (round in 1..lowerRoundCount) {
            val row = losersByRound.getValue(round)
            val nextRow = losersByRound[round + 1]
            row.forEachIndexed { index, node ->
                if (round == lowerRoundCount) {
                    node.nextWinnerKey = "G"
                    node.winnerTargetSlot = 1
                } else if (nextRow != null && nextRow.size < row.size) {
                    node.nextWinnerKey = "L${round + 1}_${index / 2}"
                    node.winnerTargetSlot = index % 2
                } else {
                    node.nextWinnerKey = "L${round + 1}_${index}"
                    node.winnerTargetSlot = 0
                }
            }
        }

        val numberByKey = nodes.mapIndexed { index, node -> node.key to index + 1 }.toMap()
        val initialSlots = mutableMapOf<String, Entrant>()
        entrants.forEachIndexed { entrantIndex, entrant ->
            val matchIndex = entrantIndex / 2
            val slot = entrantIndex % 2
            initialSlots["W1_${matchIndex}_$slot"] = entrant
        }

        return nodes.mapIndexed { index, node ->
            val one = initialSlots["${node.key}_0"]
            val two = initialSlots["${node.key}_1"]
            MatchEntity(
                tournamentId = tournamentId,
                matchNumber = index + 1,
                matchKey = node.key,
                roundNumber = node.roundNumber,
                branch = node.branch,
                status = when { one != null && two != null -> "READY"; one != null || two != null -> "WAITING_FOR_OPPONENT"; else -> "WAITING_FOR_PLAYERS" },
                player1Id = one?.playerId,
                player1NameSnapshot = one?.name,
                player2Id = two?.playerId,
                player2NameSnapshot = two?.name,
                nextWinnerMatchNumber = node.nextWinnerKey?.let(numberByKey::get),
                winnerTargetSlot = node.winnerTargetSlot,
                nextLoserMatchNumber = node.nextLoserKey?.let(numberByKey::get),
                loserTargetSlot = node.loserTargetSlot,
                isTournamentFinal = node.final
            )
        }
    }

    /** Returns match numbers in an order where every source is processed before its targets. */
    fun topologicalOrder(matches: List<MatchEntity>): List<Int> {
        val byNumber = matches.associateBy { it.matchNumber }
        val outgoing = matches.associate { it.matchNumber to mutableSetOf<Int>() }
        val indegree = matches.associate { it.matchNumber to 0 }.toMutableMap()
        matches.forEach { match ->
            listOfNotNull(match.nextWinnerMatchNumber, match.nextLoserMatchNumber)
                .filter { it in byNumber }
                .forEach { target ->
                    if (outgoing.getValue(match.matchNumber).add(target)) {
                        indegree[target] = indegree.getValue(target) + 1
                    }
                }
        }
        val queue = indegree.filterValues { it == 0 }.keys.sorted().toMutableList()
        val result = mutableListOf<Int>()
        while (queue.isNotEmpty()) {
            val current = queue.removeAt(0)
            result += current
            outgoing.getValue(current).sorted().forEach { target ->
                indegree[target] = indegree.getValue(target) - 1
                if (indegree.getValue(target) == 0) queue.add(target)
            }
        }
        check(result.size == matches.size) { "Bracketingraf obsahuje cyklus." }
        return result
    }

    private fun nextPowerOfTwo(value: Int): Int {
        var result = 1
        while (result < value) result *= 2
        return result
    }
}
