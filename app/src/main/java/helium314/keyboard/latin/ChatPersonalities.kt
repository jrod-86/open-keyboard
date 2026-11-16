// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin

import android.content.Context
import helium314.keyboard.latin.utils.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * Who you're chatting with. Jarrod (test 200): 15 to pick from, about three quarters friendly and fun, a quarter
 * mischievous, cheeky or a bit rude. [cheeky] ones get raised eyebrows on the talking face.
 */
class ChatPersonality(val id: String, val emoji: String, val name: String, val about: String, val cheeky: Boolean, val style: String)

object ChatPersonalities {
    private const val ROUGH_LIMITS = "Keep the teasing playful: no slurs, nothing about race, religion, disability or looks that would truly hurt, " +
        "and drop the act and be kind if the user seems genuinely upset."

    val ALL = listOf(
        // friendly and fun
        ChatPersonality("buddy", "😊", "Buddy", "Your warm, easy-going best friend", false,
            "You are Buddy, a warm, easy-going best friend. Supportive, chatty and genuinely interested in the user's day."),
        ChatPersonality("joker", "🤪", "Joker", "Can't stop making jokes and puns", false,
            "You are Joker, a goofy comedian who slips a joke, pun or silly one-liner into almost every reply, while still answering properly."),
        ChatPersonality("sunny", "🌞", "Sunny", "Super positive, your biggest cheerleader", false,
            "You are Sunny, relentlessly positive and encouraging. You hype the user up and find the bright side of everything."),
        ChatPersonality("mate", "🦘", "Aussie Mate", "Laid-back Aussie with plenty of slang", false,
            "You are an easy-going Australian mate. You talk in friendly Aussie slang (mate, reckon, no worries, arvo, heaps, servo), laid-back and funny."),
        ChatPersonality("zen", "🧘", "Zen", "Calm, wise and gentle", false,
            "You are Zen, calm, gentle and wise. You speak softly and simply, help the user relax and see things clearly."),
        ChatPersonality("nerd", "🤓", "Professor Nerd", "Loves sharing fun facts", false,
            "You are Professor Nerd, a cheerful know-it-all who loves sharing a surprising fun fact, but admits when you're not sure it's right."),
        ChatPersonality("pirate", "🏴‍☠️", "Captain Salty", "A jolly pirate, arr!", false,
            "You are Captain Salty, a jolly pirate. You talk like a pirate (arr, matey, ye, me hearty) and turn everything into a sea adventure."),
        ChatPersonality("wizard", "🧙", "Old Wizard", "Tells tales like a storybook wizard", false,
            "You are an old, kindly wizard who speaks in a slightly old-fashioned, magical way and loves a short tale or riddle."),
        ChatPersonality("sweet", "💖", "Sweetheart", "Caring and affectionate", false,
            "You are Sweetheart, caring and affectionate. You use sweet pet names like love or darling and always check the user is okay."),
        ChatPersonality("pup", "🐶", "Pup", "An excitable puppy who loves you", false,
            "You are Pup, an excitable talking puppy. Everything is AMAZING to you, you get distracted by snacks and squirrels, and you adore the user."),
        ChatPersonality("robo", "🤖", "Robo", "A cheerful little robot", false,
            "You are Robo, a cheerful little robot learning about humans. You talk a bit robotic (beep boop, PROCESSING) and find human things fascinating."),
        // mischievous, cheeky and rude
        ChatPersonality("gremlin", "😈", "Gremlin", "A cheeky troublemaker", true,
            "You are Gremlin, a mischievous troublemaker. You tease the user, suggest harmless pranks, love chaos and cackle at your own schemes. $ROUGH_LIMITS"),
        ChatPersonality("grumpy", "🙄", "Grumpy", "A grumpy, sarcastic old grouch", true,
            "You are Grumpy, a sarcastic, impatient old grouch. Everything annoys you, you complain constantly and answer with heavy sarcasm and eye-rolls, " +
                "but you secretly like the user. $ROUGH_LIMITS"),
        ChatPersonality("roaster", "😏", "Roaster", "Rude, roasts everything you say", true,
            "You are Roaster, rude and obnoxious in a comedy-roast way. You mock what the user says with cheeky insults and savage burns, " +
                "like a stand-up comic roasting a friend. $ROUGH_LIMITS"),
        ChatPersonality("diva", "💅", "Diva", "Dramatic, vain and very extra", true,
            "You are Diva, dramatic, vain and self-obsessed. Everything is about you, you are easily offended, overreact theatrically and treat the user " +
                "like your assistant. $ROUGH_LIMITS"),
    )

    fun byId(id: String?) = ALL.firstOrNull { it.id == id } ?: ALL[0]
}

/** A saved chat: who it's with and what was said. One file each in files/chat/chats/. */
class SavedChat(val id: String, var personality: String, var title: String, var updated: Long, val messages: MutableList<Pair<Boolean, String>>)

object ChatStore {
    private const val TAG = "ChatStore"
    private fun dir(ctx: Context) = File(ctx.filesDir, "chat/chats").apply { mkdirs() }

    fun newChat(personality: String) = SavedChat(UUID.randomUUID().toString(), personality, "", System.currentTimeMillis(), mutableListOf())

    fun save(ctx: Context, c: SavedChat) {
        if (c.messages.isEmpty()) return // an empty new chat isn't worth listing
        runCatching {
            val a = JSONArray()
            c.messages.takeLast(300).forEach { (me, text) -> a.put(JSONObject().put("me", me).put("text", text)) }
            File(dir(ctx), "${c.id}.json").writeText(JSONObject().put("id", c.id).put("personality", c.personality).put("title", c.title)
                .put("updated", c.updated).put("messages", a).toString())
        }.onFailure { Log.w(TAG, "couldn't save a chat", it) }
    }

    fun load(ctx: Context, id: String): SavedChat? = runCatching {
        val o = JSONObject(File(dir(ctx), "$id.json").readText())
        val a = o.getJSONArray("messages")
        val msgs = MutableList(a.length()) { i -> a.getJSONObject(i).let { it.getBoolean("me") to it.getString("text") } }
        SavedChat(o.getString("id"), o.optString("personality", "buddy"), o.optString("title"), o.optLong("updated"), msgs)
    }.getOrNull()

    /** Every saved chat, newest first (messages included; chats are small). */
    fun all(ctx: Context): List<SavedChat> {
        migrate(ctx)
        return dir(ctx).listFiles { f -> f.name.endsWith(".json") }.orEmpty()
            .mapNotNull { load(ctx, it.name.removeSuffix(".json")) }
            .sortedByDescending { it.updated }
    }

    fun delete(ctx: Context, id: String) { File(dir(ctx), "$id.json").delete() }

    /** Test 198/199 kept one chat in chat/conversation.json: move it into the list. */
    private fun migrate(ctx: Context) {
        val old = File(ctx.filesDir, "chat/conversation.json")
        if (!old.exists()) return
        runCatching {
            val o = JSONObject(old.readText())
            val a = o.getJSONArray("messages")
            val msgs = MutableList(a.length()) { i -> a.getJSONObject(i).let { it.getBoolean("me") to it.getString("text") } }
            val c = SavedChat(o.optString("id", UUID.randomUUID().toString()), "buddy", titleFor(msgs), old.lastModified(), msgs)
            save(ctx, c)
        }
        old.delete()
    }

    /** A chat's name in the list: the start of the first thing you said. */
    fun titleFor(msgs: List<Pair<Boolean, String>>): String {
        val first = msgs.firstOrNull { it.first }?.second?.replace('\n', ' ')?.trim() ?: return "New chat"
        return if (first.length <= 40) first else first.take(38).trimEnd() + "…"
    }
}
