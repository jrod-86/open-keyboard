// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.voice

/**
 * Where the speaker is from. The speech models are trained on many English accents and can't be
 * told an accent directly, so the region sets the spelling voice typing uses (colour or color).
 */
enum class EnglishRegion(val title: String, val britishOur: Boolean, val britishIse: Boolean) {
    US("United States", false, false),
    CA("Canada", true, false),
    UK("United Kingdom", true, true),
    AU("Australia", true, true),
    NZ("New Zealand", true, true),
    IE("Ireland", true, true),
    IN("India", true, true),
    ZA("South Africa", true, true),
}

/** Changes American spellings from the speech models into the spelling used in the chosen region. */
object VoiceSpelling {
    // colour, centre, travelled, ... (also used in Canada)
    private val our = mapOf(
        "color" to "colour", "colors" to "colours", "colored" to "coloured", "colorful" to "colourful",
        "favor" to "favour", "favors" to "favours", "favorite" to "favourite", "favorites" to "favourites",
        "favorable" to "favourable", "honor" to "honour", "honors" to "honours", "honored" to "honoured",
        "humor" to "humour", "labor" to "labour", "neighbor" to "neighbour", "neighbors" to "neighbours",
        "neighborhood" to "neighbourhood", "behavior" to "behaviour", "behaviors" to "behaviours",
        "flavor" to "flavour", "flavors" to "flavours", "harbor" to "harbour", "rumor" to "rumour",
        "rumors" to "rumours", "savior" to "saviour", "vapor" to "vapour", "armor" to "armour",
        "endeavor" to "endeavour", "glamor" to "glamour", "odor" to "odour", "parlor" to "parlour",
        "center" to "centre", "centers" to "centres", "centered" to "centred", "theater" to "theatre",
        "theaters" to "theatres", "kilometer" to "kilometre",
        "kilometers" to "kilometres", "centimeter" to "centimetre", "centimeters" to "centimetres",
        "millimeter" to "millimetre", "millimeters" to "millimetres", "liter" to "litre", "liters" to "litres",
        "fiber" to "fibre", "somber" to "sombre", "traveled" to "travelled", "traveling" to "travelling",
        "traveler" to "traveller", "travelers" to "travellers", "canceled" to "cancelled",
        "canceling" to "cancelling", "labeled" to "labelled", "labeling" to "labelling",
        "modeling" to "modelling", "fueled" to "fuelled", "jewelry" to "jewellery",
        "catalog" to "catalogue", "dialog" to "dialogue", "analog" to "analogue",
        "defense" to "defence", "offense" to "offence", "license" to "licence", "pajamas" to "pyjamas",
        "gray" to "grey",
        "aluminum" to "aluminium", 
        "skeptical" to "sceptical", "plow" to "plough", "cozy" to "cosy", "mustache" to "moustache",
        "enroll" to "enrol", "fulfill" to "fulfil", "installment" to "instalment",
    )

    // realise, organise, analyse, ...
    private val ise = mapOf(
        "realize" to "realise", "realized" to "realised", "realizes" to "realises", "realizing" to "realising",
        "organize" to "organise", "organized" to "organised", "organizes" to "organises", "organizing" to "organising",
        "organization" to "organisation", "organizations" to "organisations",
        "recognize" to "recognise", "recognized" to "recognised", "recognizes" to "recognises", "recognizing" to "recognising",
        "apologize" to "apologise", "apologized" to "apologised", "apologizing" to "apologising",
        "prioritize" to "prioritise", "prioritized" to "prioritised", "summarize" to "summarise",
        "summarized" to "summarised", "customize" to "customise", "customized" to "customised",
        "optimize" to "optimise", "optimized" to "optimised", "minimize" to "minimise", "maximize" to "maximise",
        "finalize" to "finalise", "finalized" to "finalised", "criticize" to "criticise", "emphasize" to "emphasise",
        "authorize" to "authorise", "authorized" to "authorised", "categorize" to "categorise",
        "memorize" to "memorise", "personalize" to "personalise", "personalized" to "personalised",
        "specialize" to "specialise", "specialized" to "specialised", "standardize" to "standardise",
        "utilize" to "utilise", "visualize" to "visualise", "sympathize" to "sympathise",
        "analyze" to "analyse", "analyzed" to "analysed", "analyzing" to "analysing",
        "paralyze" to "paralyse", "paralyzed" to "paralysed",
    )

    private val word = Regex("[A-Za-z]+")

    fun apply(text: String, region: EnglishRegion): String {
        if (!region.britishOur && !region.britishIse) return text
        return word.replace(text) { m ->
            val w = m.value
            val lower = w.lowercase()
            val replaced = (if (region.britishIse) ise[lower] else null) ?: (if (region.britishOur) our[lower] else null)
                ?: return@replace w
            when {
                w.length > 1 && w.all { it.isUpperCase() } -> replaced.uppercase()
                w[0].isUpperCase() -> replaced.replaceFirstChar { it.titlecase() }
                else -> replaced
            }
        }
    }
}
