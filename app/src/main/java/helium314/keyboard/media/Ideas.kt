// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.media

import android.content.Context

/**
 * The Image maker's 🎲: builds a picture idea from mix-and-match parts (who, doing what, where, when), which gives
 * hundreds of thousands of different ideas, and remembers the last 200 so the same one never comes back soon.
 * All family-friendly.
 */
object Ideas {
    private val who = listOf(
        "a tuxedo cat", "a kangaroo", "a koala", "a corgi", "a red panda", "a penguin", "a fox", "an owl", "a sloth",
        "a hedgehog", "a baby dragon", "a friendly robot", "a tiny elephant", "a polar bear", "a raccoon", "a giraffe",
        "a wombat", "a platypus", "a golden retriever", "a bunny", "a turtle", "a lion cub", "a hamster", "a parrot",
        "a frog", "a squirrel", "a panda", "a quokka", "a jellyfish", "an octopus", "a cheeky monkey", "a snowman",
        "a gingerbread man", "a teddy bear", "a wizard cat", "a knight mouse", "a little astronaut", "a pirate duck",
        "a unicorn", "a chameleon", "a cockatoo", "an echidna", "a seal pup", "a tiger", "a pug",
    )
    private val doing = listOf(
        "eating pizza", "surfing a big wave", "drinking bubble tea", "reading a book", "playing the guitar",
        "riding a bicycle", "baking cupcakes", "flying a kite", "painting a picture", "DJing at a party", "skateboarding",
        "having a picnic", "sipping hot chocolate", "fishing", "building a sandcastle", "playing chess", "gardening",
        "taking a selfie", "dancing", "making pancakes", "riding a hot air balloon", "sailing a tiny boat",
        "casting a sparkly spell", "wearing sunglasses", "wearing a tiny crown", "in a knitted jumper", "camping by a fire",
        "stargazing with a telescope", "playing in the snow", "blowing bubbles", "driving a vintage car", "eating ramen",
        "playing drums", "knitting a scarf", "sleeping in a hammock", "juggling fruit", "doing yoga", "skiing",
    )
    private val where = listOf(
        "on the beach", "on the moon", "in a snowy forest", "in a cosy cabin", "in a neon city", "under the sea",
        "in a field of sunflowers", "on a mountain top", "in a magical library", "in a Japanese garden", "in the outback",
        "on a rainy street", "in a candy land", "inside a teacup", "on a floating island", "in a bamboo forest",
        "at a carnival", "in a castle", "on a pirate ship", "in a space station", "in a jungle", "by a waterfall",
        "on a rooftop", "in a busy cafe", "in a cherry blossom park", "in a desert oasis", "on a frozen lake",
        "in a mushroom village", "in an old toy shop", "on a train", "in a hot spring", "at a night market",
    )
    private val whenLight = listOf(
        "at sunset", "at night under the stars", "in golden morning light", "in the rain", "during a snowfall",
        "under the northern lights", "on a sunny day", "in misty dawn light", "with fireworks in the sky",
        "under a full moon", "with glowing lanterns", "on a stormy evening", "in soft spring light", "at blue hour",
    )

    private const val KEEP = 200

    fun next(c: Context): String {
        val prefs = c.getSharedPreferences("image_maker", Context.MODE_PRIVATE)
        val recent = prefs.getString("dice_recent", "")!!.split('\n').filter { it.isNotEmpty() }.toMutableList()
        var idea: String
        var tries = 0
        do {
            idea = buildString {
                append(who.random()).append(' ').append(doing.random())
                if (Math.random() < 0.85) append(' ').append(where.random())
                if (Math.random() < 0.6) append(' ').append(whenLight.random())
            }
            tries++
        } while (idea in recent && tries < 50)
        recent.add(idea)
        while (recent.size > KEEP) recent.removeAt(0)
        prefs.edit().putString("dice_recent", recent.joinToString("\n")).apply()
        return idea.replaceFirstChar { it.uppercase() }
    }
}
