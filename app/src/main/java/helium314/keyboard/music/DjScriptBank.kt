// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.music

import android.content.Context
import java.util.Random

/**
 * Test 350 (Jarrod): 5-hour zero-repeat radio DJ script engine for Instant mode (and AI fallback).
 *
 * Uses persistent Fisher-Yates shuffle-bags stored in SharedPreferences so every deck plays 100%
 * of its cards in random order before reshuffling (even across app restarts / APK updates).
 * With 100 openers, 50+ middle beats, 48 intros, and 60 short liners per style, nothing repeats
 * for 5+ hours of driving even on "Every song".
 */
internal object DjScriptBank {
    private const val PREFS = "music_dj_bags"
    private val rng = Random()

    private class ExtraBank(
        val openers: List<String>,
        val middles: List<Pair<String, String>>,
        val intros: List<String>,
        val shorts: List<String>,
    )
    @Volatile private var cachedExtra: ExtraBank? = null

    private fun extra(ctx: Context): ExtraBank = cachedExtra ?: synchronized(this) {
        cachedExtra ?: run {
            val op = ArrayList<String>()
            val mid = ArrayList<Pair<String, String>>()
            val intr = ArrayList<String>()
            val sh = ArrayList<String>()
            var sec = ""
            runCatching {
                ctx.assets.open("music/dj_bank_extra.txt").bufferedReader().useLines { lines ->
                    for (raw in lines) {
                        val line = raw.trim()
                        if (line.isEmpty() || line.startsWith("#")) continue
                        if (line.startsWith("[") && line.endsWith("]")) { sec = line; continue }
                        when (sec) {
                            "[OPENERS]" -> op += line
                            "[MIDDLES]" -> line.split('|', limit = 2).takeIf { it.size == 2 }?.let { mid += it[0].trim() to it[1].trim() }
                            "[INTROS]" -> intr += line
                            "[SHORTS]" -> sh += line
                        }
                    }
                }
            }
            ExtraBank(op, mid, intr, sh).also { cachedExtra = it }
        }
    }

    /** Draws the next item from a persistent Fisher-Yates shuffle-bag (zero repeats until the full deck is exhausted). */
    private fun draw(ctx: Context, bagKey: String, items: List<String>): String {
        if (items.isEmpty()) return ""
        if (items.size == 1) return items[0]
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val raw = p.getString(bagKey, null)
        val queue = raw?.split(',')?.mapNotNull { it.toIntOrNull() }?.filter { it in items.indices }?.toMutableList()
            ?: mutableListOf()
        val lastPicked = p.getInt("${bagKey}_last", -1)
        if (queue.isEmpty()) {
            val fresh = items.indices.toMutableList()
            // Fisher-Yates shuffle
            for (i in fresh.lastIndex downTo 1) {
                val j = rng.nextInt(i + 1)
                val tmp = fresh[i]; fresh[i] = fresh[j]; fresh[j] = tmp
            }
            // Avoid boundary repeat when a deck finishes and reshuffles
            if (fresh.size > 1 && fresh[0] == lastPicked) {
                val swap = 1 + rng.nextInt(fresh.size - 1)
                val tmp = fresh[0]; fresh[0] = fresh[swap]; fresh[swap] = tmp
            }
            queue.addAll(fresh)
        }
        val idx = queue.removeAt(0)
        p.edit()
            .putString(bagKey, queue.joinToString(","))
            .putInt("${bagKey}_last", idx)
            .apply()
        return items[idx]
    }

    /** Same persistent shuffle-bag, but only draws from items whose condition is currently satisfied. */
    private fun drawEligible(ctx: Context, bagKey: String, items: List<Pair<String, String?>>): String {
        val valid = items.mapNotNull { (k, v) -> if (v != null) k to v else null }
        if (valid.isEmpty()) return ""
        if (valid.size == 1) return valid[0].second
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val validMap = valid.toMap()
        val raw = p.getString(bagKey, null)
        val used = raw?.split(',')?.filter { it.isNotEmpty() }?.toMutableSet() ?: mutableSetOf()
        var pool = valid.filter { it.first !in used }
        if (pool.isEmpty()) {
            // Every eligible card has been heard: reset the bag (keeping only the very last card out)
            val lastKey = p.getString("${bagKey}_last", null)
            used.clear()
            if (lastKey != null && valid.size > 1) used.add(lastKey)
            pool = valid.filter { it.first !in used }.ifEmpty { valid }
        }
        val pick = pool[rng.nextInt(pool.size)]
        used.add(pick.first)
        p.edit()
            .putString(bagKey, used.joinToString(","))
            .putString("${bagKey}_last", pick.first)
            .apply()
        return pick.second
    }

    fun build(ctx: Context, styleIdx: Int, lenIdx: Int, cur: Song?, next: Song, mentionStation: Boolean, timeOfDay: String): String {
        val t = next.title
        val a = next.artist
        val yrNum = next.year.takeIf { it > 0 }
        val yr = if (yrNum != null) " from $yrNum" else ""
        val decade = yrNum?.let { "${(it / 10) * 10}s" }
        val prevA = cur?.artist?.takeIf { it.isNotBlank() && it != "Unknown artist" }
        val prevT = cur?.title?.takeIf { it.isNotBlank() }
        val genre = next.genre.split(',', '/', ';').firstOrNull { it.isNotBlank() }?.trim()?.takeIf { it.length in 3..24 }
        val pop = Popularity.known(ctx, next)
        val chartFact = pop?.let {
            val spot = listOfNotNull(
                it.au.takeIf { n -> n > 0 }?.let { n -> "number $n in Australia" },
                it.us.takeIf { n -> n > 0 }?.let { n -> "number $n in the US" },
                it.uk.takeIf { n -> n > 0 }?.let { n -> "number $n in the UK" },
            ).firstOrNull()
            val y = it.year.takeIf { v -> v > 0 } ?: yrNum
            when {
                spot != null && y != null -> "That one originally reached $spot back in $y."
                spot != null -> "That one originally hit $spot when it came out."
                else -> null
            }
        }

        val stSentence = if (mentionStation) draw(ctx, "st_id", listOf(
            "You're listening to Open Waves.",
            "Right here on Open Waves.",
            "This is Open Waves.",
            "Welcome back to Open Waves.",
            "You're tuned in to Open Waves.",
            "Non-stop music on Open Waves.",
            "Live from your library on Open Waves.",
            "All your favourites, right here on Open Waves.",
        )) else ""

        fun fill(s: String, includeYr: Boolean = true): String = s
            .replace("{t}", t)
            .replace("{a}", a)
            .replace("{yr}", if (includeYr) yr else "")
            .replace("{tod}", timeOfDay)

        val ex = extra(ctx)

        // 1. Short (~10-16 words / ~5-7 s): 150 unique 1-liners per style (~8.75h zero repeat on Every song)
        if (lenIdx == 0) {
            val line = fill(draw(ctx, "short_$styleIdx", (shortLines(styleIdx) + ex.shorts).distinct()), includeYr = false)
            return if (stSentence.isNotEmpty()) "$stSentence $line" else line
        }

        // 2. Draw from independent persistent shuffle-bags (270 openers = ~15.75h zero repeat even on Every song!)
        val opener = fill(draw(ctx, "open_$styleIdx", (openers(styleIdx) + ex.openers).distinct()))
        val middle = if (lenIdx == 2) drawEligible(ctx, "mid_bag", middleBeats(prevA, prevT, yrNum, decade, genre, chartFact, timeOfDay) + ex.middles) else ""
        val yrAlreadySaid = yrNum != null && (yrNum.toString() in middle || yrNum.toString() in opener)
        val intro = fill(draw(ctx, "intro_$styleIdx", (intros(styleIdx) + ex.intros).distinct()), includeYr = !yrAlreadySaid)

        // 3. Rotate through 6 natural radio-DJ structures (also on a shuffle-bag!)
        val pattern = draw(ctx, "pat_$lenIdx", listOf("0", "1", "2", "3", "4", "5")).toInt()
        val parts = if (lenIdx == 2) {
            when (pattern) {
                0 -> listOf(stSentence, opener, middle, intro)
                1 -> listOf(opener, stSentence, middle, intro)
                2 -> listOf(middle, opener, stSentence, intro)
                3 -> listOf(opener, middle, stSentence, intro)
                4 -> listOf(stSentence, middle, opener, intro)
                else -> listOf(middle, stSentence, opener, intro)
            }
        } else {
            when (pattern % 3) {
                0 -> listOf(stSentence, opener, intro)
                1 -> listOf(opener, stSentence, intro)
                else -> listOf(opener, intro, stSentence)
            }
        }
        return parts.filter { it.isNotBlank() }.joinToString(" ")
    }

    // ---- 60 unique Short lines per style (15 style-specific + 45 universal) ----
    private fun shortLines(styleIdx: Int): List<String> {
        val specific = when (styleIdx) {
            1 -> listOf(
                "Keep the energy loud — up next, {t} by {a}!",
                "Turn the volume right up, here comes {t} by {a}!",
                "No slowing down now — dropping {t} by {a}!",
                "Big tune incoming, get ready for {t} by {a}!",
                "Hands up for this one — here's {a} with {t}!",
                "Wake up the speakers — rolling straight into {t} by {a}!",
                "Momentum's sky high right now, cueing up {t} by {a}!",
                "Time to crank the bass — coming up, {t} by {a}!",
                "Oh yeah, this one always hits — {t} by {a}!",
                "Let's keep the pace flying with {a} and {t}!",
                "Don't touch that dial — here comes {t} by {a}!",
                "Here's a proper crowd-pleaser — {t} by {a}!",
                "Full throttle into the next track — {t} by {a}!",
                "Feel the beat kick in — up next, {t} by {a}!",
                "Raising the roof right now with {a} and {t}!",
            )
            2 -> listOf(
                "Easy does it this {tod} — drifting into {t} by {a}.",
                "Kick back and unwind with {t} by {a}.",
                "Keeping things smooth and unhurried — up next, {t} by {a}.",
                "Take a breather and enjoy this one, {t} by {a}.",
                "Dim the lights and let this one carry you — {t} by {a}.",
                "Smooth, mellow, and right on cue — here's {a} with {t}.",
                "Just good music, no rush — rolling into {t} by {a}.",
                "Easing gently into our next track, {t} by {a}.",
                "Sink into the groove with {a} and {t}.",
                "Quiet moments deserve a great soundtrack — here's {t} by {a}.",
                "Letting the melody take over now with {t} by {a}.",
                "Soft, steady, and easy on the ears — {t} by {a}.",
                "Slowing the world down a little with {t} by {a}.",
                "Warm tones and good company — coming up, {t} by {a}.",
                "Floating right into a smooth one — {a} with {t}.",
            )
            3 -> listOf(
                "Zero commercials, just great taste — up next, {t} by {a}!",
                "If you don't hum along, I'm quietly judging you — here's {t} by {a}!",
                "No skip button allowed on this one — here comes {t} by {a}!",
                "Scientifically proven to lift your mood, probably — {t} by {a}!",
                "Your algorithm could never pick this well — up next, {t} by {a}!",
                "Officially the best excuse to turn it up — {a} with {t}!",
                "Your playlist is doing all the heavy lifting today — {t} by {a}!",
                "No traffic report, no weather, just {t} by {a}!",
                "Not taking requests, I'm already playing {t} by {a}!",
                "Good news: you have great taste. Proof: {t} by {a}!",
                "Try not to tap the steering wheel to {t} by {a}!",
                "Zero awkward morning-show banter, just {t} by {a}!",
                "Legally required to be played loud — here's {t} by {a}!",
                "Better than any FM station out there — {a} with {t}!",
                "Studio budget: zero dollars. Song quality: ten out of ten. Here's {t} by {a}!",
            )
            else -> listOf(
                "Hope your {tod} is going great — up next, {t} by {a}.",
                "Loving how this playlist flows — rolling into {t} by {a}.",
                "Here's a great one coming up now — {t} by {a}.",
                "Can't go wrong with this track — next up, {a} with {t}.",
                "Keeping the good tunes moving with {t} by {a}.",
                "Always a pleasure spinning this one — here's {t} by {a}.",
                "Hope this next track puts a smile on your face — {t} by {a}.",
                "Great song on deck right now — this is {t} by {a}.",
                "Smoothly into the next tune, cueing up {t} by {a}.",
                "Right on cue for your {tod} — over to {a} with {t}.",
                "Nothing beats your own music — bringing in {t} by {a}.",
                "One great track right into the next — {t} by {a}.",
                "Glad to have you along this {tod} — here's {t} by {a}.",
                "Another favourite lined up right behind it — {t} by {a}.",
                "Keeping the soundtrack strong with {a} and {t}.",
            )
        }
        val universal = listOf(
            "Ready for another classic? Here's {t} by {a}.",
            "Straight from your collection — {t} by {a}.",
            "Now stepping up to the mic, {a} with {t}.",
            "You picked a winner here — coming up, {t} by {a}.",
            "Letting this next groove take over — {t} by {a}.",
            "Right on time for your {tod} — {a} and {t}.",
            "Next in the spotlight, it's {t} by {a}.",
            "Sliding seamlessly into {t} by {a}.",
            "Always hits the mark — here comes {t} by {a}.",
            "Fresh on the turntable, {a} with {t}.",
            "Keep those speakers happy — up next, {t} by {a}.",
            "A standout track from your library — {t} by {a}.",
            "Jumping straight into {t} by {a}.",
            "Here's one you'll know in two seconds — {t} by {a}.",
            "Rolling right along with {a} and {t}.",
            "Time for a proper favourite — {t} by {a}.",
            "Next up on the deck, {t} by {a}.",
            "Keeping the wheels turning with {t} by {a}.",
            "Let's hear it for {a} with {t}.",
            "Bringing {t} by {a} straight to your speakers.",
            "This one never gets old — {t} by {a}.",
            "Queueing up a beauty right now — {t} by {a}.",
            "Handing the floor to {a} with {t}.",
            "Next chapter in the mix — {t} by {a}.",
            "Good vibes locked in — here's {t} by {a}.",
            "Spinning up {t} by {a} right now.",
            "Another gem from your stash — {t} by {a}.",
            "Letting {a} take the lead with {t}.",
            "No filler here — straight into {t} by {a}.",
            "One of the best in the queue — {t} by {a}.",
            "Keeping your {tod} moving with {t} by {a}.",
            "Here's a track that always delivers — {t} by {a}.",
            "Turning the page to {a} and {t}.",
            "Right where the mix wants to go — {t} by {a}.",
            "Cruising right along with {t} by {a}.",
            "Lining up {a} next with {t}.",
            "Always worth turning up a notch — {t} by {a}.",
            "Back-to-back greats — next is {t} by {a}.",
            "Dropping {a} into the mix with {t}.",
            "Your soundtrack keeps rolling with {t} by {a}.",
            "A sure-fire favourite coming up — {t} by {a}.",
            "Setting the tone with {t} by {a}.",
            "Ready when you are — here's {t} by {a}.",
            "Keeping the rhythm alive with {a} and {t}.",
            "Straight into {t} by {a} — enjoy!",
        )
        return specific + universal
    }

    // ---- 100 unique Openers per style (30 style-specific + 70 universal = ~5.8 hours zero repeat!) ----
    private fun openers(styleIdx: Int): List<String> {
        val specific = when (styleIdx) {
            1 -> listOf(
                "Oh yeah, we are not slowing down for a second this {tod}!",
                "Hope you've got the volume cranked up because this mix is on fire!",
                "What a run of tunes we've got rolling right now!",
                "Keeping the energy sky high for your {tod} drive!",
                "If you're cruising, working, or just vibing out, turn those speakers up!",
                "No brakes on this playlist — every track is hitting just right!",
                "Hands on the wheel and volume on high, because we're keeping the party moving!",
                "That bassline energy isn't going anywhere anytime soon!",
                "Big soundtrack energy coming through loud and clear this {tod}!",
                "Every single track on this list has been an absolute winner so far!",
                "Let's keep the adrenaline pumping with another massive favourite!",
                "You've built a seriously unstoppable playlist here!",
                "Speakers warmed up and ready? Because we're only getting louder from here!",
                "Zero dull moments on this deck — pure momentum track after track!",
                "If you needed a boost this {tod}, this playlist has you completely covered!",
                "Nothing wakes up the cabin like a back-to-back run of heavy hitters!",
                "We're firing on all cylinders right now — don't even think about turning it down!",
                "This is the kind of tracklist that makes the miles fly right by!",
                "Energy levels are through the roof today and we're keeping it right there!",
                "No fillers, no slowdowns — just wall-to-wall anthems all {tod}!",
                "Hope your subwoofers are ready for a proper workout!",
                "We are locked in and rolling hard through your best tracks!",
                "That last chorus barely faded and we're already back at full power!",
                "Nothing beats high-octane tunes when you've got ground to cover!",
                "Keep the energy dial pinned all the way to the right!",
                "This mix hits like an espresso shot for the ears!",
                "Every transition today is pure gold — let's keep it roaring!",
                "If you're not nodding along yet, this next track is going to fix that!",
                "We're running hot with zero plans to cool down!",
                "Turn up the stereo and let this next anthem do its thing!",
            )
            2 -> listOf(
                "Hope this {tod} is treating you gently out there.",
                "Take a deep breath, kick back, and let the music carry you along.",
                "There's nothing better than letting the pace slow down with the right soundtrack.",
                "Easy listening all the way — no rush, no noise, just great songs.",
                "Keeping the mood smooth, warm, and effortless for you.",
                "Let everything else fade into the background for a few minutes.",
                "Just good music flowing quietly from one favourite to the next.",
                "Sometimes all you need is a steady groove and a little peace and quiet.",
                "Settling right into the pocket with another laid-back classic.",
                "Low-key, unhurried, and exactly the right energy for this {tod}.",
                "Letting the rhythm breathe and taking our time with every track.",
                "Smooth sailing all the way through your collection.",
                "No hurry at all — just soft lights, clear roads, and good melodies.",
                "Let your shoulders drop and enjoy a few calm minutes with this next one.",
                "The kind of effortless flow that makes you forget to check the clock.",
                "Keeping the atmosphere relaxed and easy on the ears.",
                "Every song has its own space to shine when the mix moves this smoothly.",
                "A quiet little pocket of great music right in the middle of your {tod}.",
                "Warm tones, steady beat, and zero distractions.",
                "Just leaning back and letting your library do its thing.",
                "No static, no rush — just pure calm coming through the speakers.",
                "Letting the melody drift easy from one track to the next.",
                "Soft edges and golden harmonies all the way through.",
                "The best kind of company is a playlist that knows how to keep it mellow.",
                "Taking the scenic route through your favourite chill tracks.",
                "Everything feels a little lighter when the right song comes on.",
                "Unwinding one track at a time with zero pressure.",
                "Here's to quiet roads, smooth beats, and easy listening.",
                "Letting the tension melt away while the next tune rolls in.",
                "Pure comfort music, lined up and ready whenever you are.",
            )
            3 -> listOf(
                "Still zero commercials, zero traffic updates, and zero awkward radio jingles.",
                "Your streaming algorithm could spend a decade trying and still never build a run like this.",
                "I'd read you the weather forecast, but honestly you're way better off just listening to the music.",
                "Good news: your music taste is officially undefeated this {tod}.",
                "No callers, no prize giveaways, and no annoying airhorns — just your own bangers.",
                "Scientifically proven to make any {tod} at least forty percent better — probably.",
                "Your playlist is doing all the heavy lifting right now and I'm just here to take the credit.",
                "Fair warning: if you don't at least mouth the chorus on this next one, I am quietly judging you.",
                "No, I'm not taking requests — mostly because you already picked a better song than I could.",
                "Best radio station on your phone, and the studio budget is literally zero dollars.",
                "Try not to tap the steering wheel or nod your head on this next track — impossible challenge.",
                "Who needs a morning show crew laughing at their own jokes when you've got tunes like these?",
                "Quick station announcement: there is no announcement, I just really wanted to play this next song.",
                "If anyone in traffic is wondering why you're grinning, blame this playlist.",
                "I checked the rules and it's actually illegal to listen to this next one at low volume.",
                "Not a single sponsored mattress ad in sight — just wall-to-wall favourites.",
                "Honestly, whoever put this music library together deserves a raise.",
                "No fake radio drama, no prank calls — just pure, unfiltered good taste.",
                "Consider this your official permission slip to turn the stereo up two more clicks.",
                "Breaking news: this next song is still way better than anything on regular FM radio.",
                "I was going to tell a joke, but this next track is way more entertaining than I am.",
                "Zero buffering, zero monthly subscription nagging — just your own greatest hits.",
                "If this playlist were a restaurant, it'd already have three Michelin stars.",
                "Reminder: air-drumming at red lights is completely encouraged on this station.",
                "No news bulletin right now — spoiler alert, the music is better than the headlines anyway.",
                "My legal team says I have to warn you how catchy this next chorus is.",
                "Regular radio plays the same six songs all week; meanwhile, look at the gems you've got here.",
                "No annoying car-dealership ads yelling at you — just pure peace and great tracks.",
                "I'd ask how your {tod} is going, but with a queue like this, I already know the answer.",
                "Fun fact: studies I just made up confirm this next song improves driving mood by ninety percent.",
            )
            else -> listOf(
                "Hope you're having an awesome {tod} and enjoying the tunes!",
                "So good to have you along for the ride this {tod}.",
                "Nothing beats your own music collection rolling smoothly from one great song to the next.",
                "Loving the flow of this playlist — every track has been landing right on the mark.",
                "Wherever you're headed this {tod}, you've got a cracking soundtrack for it.",
                "Thanks for hanging out and letting the good songs roll.",
                "Always a pleasure spinning through your favourites with you.",
                "That's the beauty of your own library — zero filler, just the songs you actually love.",
                "Keeping the good company and great melodies going strong this {tod}.",
                "One great track wraps up and another favourite is already lined up on the deck.",
                "Hope the road ahead is smooth and the speakers are sounding great.",
                "You really can't beat a solid run of classics to keep the {tod} moving nicely.",
                "Great mix of tracks coming through today — really enjoying this session.",
                "Just the right soundtrack to make a busy {tod} feel a whole lot easier.",
                "Every time you think this playlist can't top the last song, another gem shows up.",
                "Glad to be riding shotgun with your music this {tod}.",
                "Nothing fancy, just great songs back to back the way radio should be.",
                "Here's to a smooth {tod} and plenty more classics waiting in the queue.",
                "It's always a good time when your favourite artists keep showing up one after another.",
                "Keeping the tunes fresh and the vibes steady right through the {tod}.",
                "Hope everything's going smoothly wherever you're tuning in from.",
                "A good song at the right moment can turn a whole {tod} around.",
                "Really digging the variety in this set so far.",
                "Your personal radio booth is humming along nicely today.",
                "It's hard to beat the comfort of hearing songs you hand-picked yourself.",
                "Cheers for keeping the music rolling with me this {tod}.",
                "Another smooth handover coming right up from the studio deck.",
                "Just settling into a great rhythm with your library right now.",
                "Plenty more miles and plenty more great music still ahead.",
                "Love how effortlessly this mix is coming together today.",
            )
        }
        val universal = listOf(
            "Alright, let's keep the wheels turning and the music playing.",
            "Time to switch over to the next deck.",
            "Here's another track that earned its permanent spot in your library.",
            "Always love when this part of the queue rolls around.",
            "No downtime on the airwaves — straight into the next pick.",
            "You've got a seriously deep bench of great songs in here.",
            "Let's keep the soundtrack rolling without missing a beat.",
            "Funny how the exact right song always seems to pop up next.",
            "Keeping the stereo busy this {tod} with another standout.",
            "Every good drive deserves a run of tracks like this.",
            "Let's see what's waiting for us right at the top of the queue.",
            "We're cruising nicely through your collection right now.",
            "Time for another familiar intro that hits straight away.",
            "Not a single weak track in this whole stretch.",
            "Your library never runs short on great moments.",
            "Keeping the good stuff coming straight to your speakers.",
            "Let's roll the next track onto the turntable.",
            "Always a good sign when you don't even think about hitting skip.",
            "Right on schedule with another top-shelf tune.",
            "Moving smoothly down the list with zero dead air.",
            "There's a reason this next song has stayed in your rotation.",
            "Just the right pick to carry this {tod} forward.",
            "Here's a track that always brings its own atmosphere.",
            "We're right in the sweet spot of the playlist now.",
            "Letting the next artist step up to the microphone.",
            "Nothing like a clean mix when you're in the zone.",
            "Ready for another round of great music?",
            "Keeping the sound crisp and the queue stacked.",
            "Always feels good when a favourite like this cues up.",
            "Let's keep the momentum rolling right along.",
            "One more reason to leave the radio dial right where it is.",
            "This stretch of songs has been pure class from start to finish.",
            "Taking care of the soundtrack while you focus on the road.",
            "Got another crowd favourite warmed up and ready to go.",
            "Let's drop the needle on the next track.",
            "Your collection really shines when it's flowing like this.",
            "Zero distractions, just wall-to-wall tunes you actually know.",
            "Moving right along to the next highlight in your queue.",
            "That's how you keep a {tod} sounding good.",
            "Time to cue up another timeless pick.",
            "Really loving the contrast from track to track today.",
            "Keeping your personal station running like clockwork.",
            "Here's one that always sounds great on a proper set of speakers.",
            "No slowing down the flow — the next tune is right here.",
            "Always fun seeing what your library pulls out of the hat next.",
            "Let's keep this winning streak going strong.",
            "Another solid gold entry stepping into the spotlight.",
            "Smooth transitions and great tracks — that's the whole recipe.",
            "Ready to keep your {tod} soundtrack firing nicely.",
            "You can tell a lot of good memories live in this playlist.",
            "Right on cue, here comes the next chapter of the mix.",
            "Just good sound, good timing, and zero interruptions.",
            "Let's hand the speakers over to a proper classic.",
            "This mix has been hitting every right note so far.",
            "Keeping the needle moving through your favourites.",
            "Here's another track that needs no introduction — though I'll give it one anyway.",
            "Staying locked into the groove with our next selection.",
            "Every hour goes by faster with a lineup like this.",
            "Pulling another gem straight off the shelf for you.",
            "Let's keep the cabin sounding sharp this {tod}.",
            "Always nice when the queue delivers back-to-back winners.",
            "Onward to the next track in your personal rotation.",
            "No filler tracks allowed on this watch.",
            "Keeping the signal strong and the music flowing.",
            "Time to bring another heavy hitter onto the deck.",
            "This next one's been waiting patiently in the queue for its turn.",
            "You've got taste — and the next track proves it all over again.",
            "Letting the music do the talking this {tod}.",
            "Another great pick lined up right on the beat.",
            "Here we go again with another standout from your collection.",
        )
        return specific + universal
    }

    // ---- 40+ dynamic Middle beats (tracked by unique key so no template repeats until the whole bag is played) ----
    private fun middleBeats(
        prevA: String?, prevT: String?, yrNum: Int?, decade: String?,
        genre: String?, chartFact: String?, timeOfDay: String
    ): List<Pair<String, String?>> = listOf(
        "prev_1" to (if (prevA != null && prevT != null) "That was $prevT by $prevA setting the tone nicely." else null),
        "prev_2" to (if (prevA != null && prevT != null) "We just wrapped up $prevA with $prevT, and the next pick fits right in." else null),
        "prev_3" to (if (prevA != null && prevT != null) "Letting $prevT by $prevA fade out smoothly behind us." else null),
        "prev_4" to (if (prevA != null && prevT != null) "$prevA did a great job with $prevT, and we're keeping that momentum alive." else null),
        "prev_5" to (if (prevA != null && prevT != null) "Coming off $prevT by $prevA, we've got another killer lined up." else null),
        "prev_6" to (if (prevA != null && prevT != null) "Shout-out to $prevA for $prevT — tough act to follow, but we're ready." else null),
        "prev_7" to (if (prevA != null && prevT != null) "As the last notes of $prevT drift away, the next deck is already spinning." else null),
        "prev_8" to (if (prevA != null && prevT != null) "That run from $prevA hit the mark, and this next choice keeps the streak going." else null),
        "preva_1" to (if (prevA != null) "Nice finish there from $prevA — now let's keep the flow moving." else null),
        "preva_2" to (if (prevA != null) "That last one from $prevA set the vibe nicely." else null),
        "preva_3" to (if (prevA != null) "Letting $prevA fade out smoothly as we cue up the next deck." else null),
        "preva_4" to (if (prevA != null) "Always a treat hearing $prevA in the mix." else null),
        "chart_1" to chartFact,
        "chart_2" to chartFact?.let { "Fun bit of music history on this next one: ${it.replaceFirstChar { c -> c.lowercase() }}" },
        "chart_3" to chartFact?.let { "Throwback stat for you — ${it.replaceFirstChar { c -> c.lowercase() }}" },
        "yr_1" to yrNum?.let { "We're taking a quick trip back to $it for this next one." },
        "yr_2" to yrNum?.let { "This one's been a favourite ever since it first came out in $it." },
        "yr_3" to yrNum?.let { "Throwing it back to $it with a track that still holds up today." },
        "yr_4" to yrNum?.let { "Straight out of $it, and it still sounds just as sharp." },
        "yr_5" to yrNum?.let { "Hard to believe this track first dropped back in $it." },
        "yr_6" to yrNum?.let { "A genuine time capsule from $it that still hits hard." },
        "dec_1" to decade?.let { "Nothing quite matches that unmistakable $decade sound." },
        "dec_2" to decade?.let { "Dipping right back into the $decade vault for this next track." },
        "dec_3" to decade?.let { "The $decade really knew how to make a hook that sticks with you." },
        "gen_1" to genre?.let { "A proper slice of $genre ready to roll next." },
        "gen_2" to genre?.let { "Keeping that $genre energy alive on the deck." },
        "gen_3" to genre?.let { "If you love good $genre, this next one is right in your lane." },
        "gen_4" to genre?.let { "Serving up a classic dose of $genre right now." },
        "u_1" to "It's the kind of track you hear the first two seconds of and immediately recognise.",
        "u_2" to "Always hits just as good as the very first time you heard it.",
        "u_3" to "Definitely one of those songs that deserves a little extra volume.",
        "u_4" to "Whether you're singing along or just cruising, this one never misses.",
        "u_5" to "One of those unmistakable intros that grabs your attention straight away.",
        "u_6" to "Hard to beat a track with this much character.",
        "u_7" to "Always a welcome surprise when this one pops up in the queue.",
        "u_8" to "A real standout in your collection — glad it landed in this mix.",
        "u_9" to "The production on this next track still sounds crisp as ever.",
        "u_10" to "Some songs fade out over time, and some just get better every listen.",
        "u_11" to "Perfect pacing for this part of your $timeOfDay.",
        "u_12" to "You know a track is special when the chorus lives rent-free in your head.",
        "u_13" to "One of those timeless melodies that bridges every mood.",
        "u_14" to "Built for open roads and loud speakers.",
        "u_15" to "Every playlist needs a moment like this one.",
        "u_16" to "Pure ear-candy from the opening bar to the fade-out.",
        "u_17" to "This one always earns its keep whenever it shows up.",
        "u_18" to "Just the right spark to keep the session feeling fresh.",
        "u_19" to "A track you can play a hundred times and still enjoy every second.",
        "u_20" to "Instant mood-setter right from the very first beat.",
    )

    // ---- 48 unique Intro endings per style (12 style-specific + 36 universal) ----
    private fun intros(styleIdx: Int): List<String> {
        val specific = when (styleIdx) {
            1 -> listOf(
                "Get ready to turn it up for {t} by {a}{yr}!",
                "Dropping {t} by {a}{yr} right now — let's go!",
                "Crank the speakers, here comes {t} by {a}{yr}!",
                "Coming in hot right now, it's {a} with {t}{yr}!",
                "No holding back — here's {t} by {a}{yr}!",
                "Turning it all the way up for {t} by {a}{yr}!",
                "Hands up for {a} and {t}{yr}!",
                "Letting {t} by {a}{yr} rip right now!",
                "Brace yourself — next up is {t} by {a}{yr}!",
                "Bringing the roof down with {a} and {t}{yr}!",
                "Volume knob to the right for {t} by {a}{yr}!",
                "Hitting the gas with {t} by {a}{yr}!",
            )
            2 -> listOf(
                "Drifting smoothly into our next track, here's {t} by {a}{yr}.",
                "Easing right into {t} by {a}{yr}.",
                "Rolling gently into the next one — {a} with {t}{yr}.",
                "Settle in as we bring up {t} by {a}{yr}.",
                "Letting this next one take over — here's {t} by {a}{yr}.",
                "Floating quietly into {t} by {a}{yr}.",
                "Taking it nice and easy with {a} and {t}{yr}.",
                "Gliding straight into {t} by {a}{yr}.",
                "Here's {t} by {a}{yr} to keep the mood right.",
                "Letting {a} take the wheel now with {t}{yr}.",
                "Slowing down for a classic — {t} by {a}{yr}.",
                "Softly bringing in {t} by {a}{yr}.",
            )
            3 -> listOf(
                "Coming up right now, {t} by {a}{yr}!",
                "Up next on your unofficial favourite station, {t} by {a}{yr}.",
                "Cueing up {a} with {t}{yr} — enjoy!",
                "Here comes {t} by {a}{yr}, no skipping allowed.",
                "Rolling straight into {t} by {a}{yr}!",
                "Over to {a} and {t}{yr}.",
                "Handing the mic straight to {a} with {t}{yr}.",
                "Next on the deck: {t} by {a}{yr} — you're welcome.",
                "Let's see if you still remember every word to {t} by {a}{yr}.",
                "Spinning up {t} by {a}{yr} right now!",
                "Time for {a} to steal the show with {t}{yr}.",
                "Dropping {t} by {a}{yr} — go ahead and sing along.",
            )
            else -> listOf(
                "Coming up right now, here's {t} by {a}{yr}.",
                "Rolling smoothly into {t} by {a}{yr}.",
                "Next up on the deck, it's {a} with {t}{yr}.",
                "Cueing up a great one for you — {t} by {a}{yr}.",
                "Here comes {t} by {a}{yr}.",
                "Let's jump right into {t} by {a}{yr}.",
                "Bringing in {a} with {t}{yr}.",
                "Up next for your {tod}, {t} by {a}{yr}.",
                "Keeping things moving nicely with {t} by {a}{yr}.",
                "Sliding straight into {a} and {t}{yr}.",
                "Here's a favourite — {t} by {a}{yr}.",
                "Over to {a} now with {t}{yr}.",
            )
        }
        val universal = listOf(
            "Take it away, {a} — here's {t}{yr}.",
            "On deck right now, {t} by {a}{yr}.",
            "Let's give a spin to {t} by {a}{yr}.",
            "Next in line: {a} with {t}{yr}.",
            "Kicking off {t} by {a}{yr} right now.",
            "Here's {a} stepping up with {t}{yr}.",
            "Enjoy this one — {t} by {a}{yr}.",
            "Straight into {t} by {a}{yr}.",
            "Now playing next, {t} by {a}{yr}.",
            "All yours — {t} by {a}{yr}.",
            "Let's hear {t} by {a}{yr}.",
            "Presenting {a} with {t}{yr}.",
            "Right here, right now, {t} by {a}{yr}.",
            "Sending {t} by {a}{yr} through your speakers.",
            "Next up, it's {t} from {a}{yr}.",
            "Here's a beauty from {a} — {t}{yr}.",
            "Rolling out {t} by {a}{yr}.",
            "Time for {t} by {a}{yr}.",
            "Check this one out — {t} by {a}{yr}.",
            "Stepping into the spotlight, {a} with {t}{yr}.",
            "Your next track is {t} by {a}{yr}.",
            "Bringing {t} by {a}{yr} to the front.",
            "Let's roll with {a} and {t}{yr}.",
            "Here's {t} by {a}{yr} coming right at you.",
            "Cue the intro — it's {t} by {a}{yr}.",
            "Next on the roster, {a} with {t}{yr}.",
            "Launching straight into {t} by {a}{yr}.",
            "Putting {t} by {a}{yr} on the air.",
            "Making room for {a} and {t}{yr}.",
            "Here we go with {t} by {a}{yr}.",
            "Letting {t} by {a}{yr} take centre stage.",
            "Up next on your speakers, {t} by {a}{yr}.",
            "Serving up {t} by {a}{yr}.",
            "Switching over to {a} with {t}{yr}.",
            "Enjoy the ride with {t} by {a}{yr}.",
            "Dropping the needle on {t} by {a}{yr}.",
        )
        return specific + universal
    }
}
