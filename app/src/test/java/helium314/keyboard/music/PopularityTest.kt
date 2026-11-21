// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.music

import org.junit.Assert.assertArrayEquals
import org.junit.Test

class PopularityTest {
    @Test fun singleChartTemplates() {
        val wt = """
{{Infobox song}}
==Charts==
{| class="wikitable"
{{single chart|Australia|1|artist=Ed Sheeran|song=Shape of You}}
{{single chart|Billboardhot100|1|artist=Ed Sheeran|song=Shape of You|rowheader=true}}
{{Single chart|UK2|1|artist=Ed Sheeran}}
{{single chart|Billboardadultpopsongs|2|artist=Ed Sheeran}}
|}"""
        assertArrayEquals(intArrayOf(1, 1, 1), Popularity.peaksIn(wt))
    }
    @Test fun oldTables() {
        val wt = """
{| class="wikitable"
|-
|US ''[[Billboard Hot 100|Billboard]]'' Hot 100
| 7
|-
|US ''Billboard'' Adult Contemporary
| 2
|-
|Australia ([[ARIA Charts|ARIA]]) || 12
|}"""
        assertArrayEquals(intArrayOf(7, 12, 0), Popularity.peaksIn(wt))
    }
}
