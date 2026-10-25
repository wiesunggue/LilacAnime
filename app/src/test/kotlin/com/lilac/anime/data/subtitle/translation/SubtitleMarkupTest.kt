package com.lilac.anime.data.subtitle.translation

import org.junit.Assert.assertEquals
import org.junit.Test

class SubtitleMarkupTest {
    @Test fun fontTagsAndEscapedTagsNeverBecomeVisible() {
        for (text in listOf("<font color=\"red\">안녕</font>", "&lt;font color=\"red\"&gt;안녕&lt;/font&gt;",
            "&amp;lt;font color=&#34;red&#34;&amp;gt;안녕&amp;lt;/font&amp;gt;", "&#60;font&#62;안녕&#60;/font&#62;")) {
            assertEquals("안녕", SubtitleMarkup.plainText(text))
        }
    }
    @Test fun lineBreaksAndUnicodeEntitiesSurvive() {
        assertEquals("안녕\n세상 & 친구", SubtitleMarkup.plainText("<i>안녕</i><br/>세상&nbsp;&amp; 친구"))
        assertEquals("< 3 > 1", SubtitleMarkup.plainText("&lt; 3 &gt; 1"))
        assertEquals("{비밀} 1 < 2", SubtitleMarkup.plainText("{비밀} 1 < 2"))
    }
    @Test fun assTagsArePreservedOnlyOnRenderBoundary() {
        val text = "{\\an8}{\\i1}<font color='red'>위쪽</font>\\N두 줄{\\i0}"
        assertEquals("위쪽\\N두 줄", SubtitleMarkup.plainText(text))
        assertEquals("{\\an8}{\\i1}위쪽\\N두 줄{\\i0}", SubtitleMarkup.stripHtml(text))
    }
    @Test fun webVttTagsAndModelMarkersDoNotLeak() {
        assertEquals("안녕", SubtitleMarkup.plainText("<LILAC_1><v Speaker><c.yellow><00:01.234>안녕</c></v>"))
    }
}
