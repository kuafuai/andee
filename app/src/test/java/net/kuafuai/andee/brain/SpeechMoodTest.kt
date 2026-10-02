package net.kuafuai.andee.brain

import net.kuafuai.andee.ui.ball.Mood
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [SpeechMood] is offset arithmetic over two alphabets, and every way it can be
 * wrong is invisible on the device: a cue one character off still lands on the
 * right sentence, a swallowed space is only audible, and a marker that survives
 * is read out loud as a word nobody notices was not in the answer. Hence a test.
 */
class SpeechMoodTest {

    @Test
    fun `no markers is the identity`() {
        val s = SpeechMood.parse("好的，我看一下。")
        assertEquals("好的，我看一下。", s.text)
        assertEquals(emptyList<SpeechMood.Cue>(), s.cues)
    }

    @Test
    fun `opening marker leaves no leading space`() {
        val s = SpeechMood.parse("[happy] 找到了。")
        assertEquals("找到了。", s.text)
        assertEquals(listOf(SpeechMood.Cue(0, Mood.HAPPY)), s.cues)
        assertEquals(Mood.HAPPY, s.opening)
    }

    @Test
    fun `a marker between English words keeps exactly one space`() {
        val s = SpeechMood.parse("ok [happy] yes")
        assertEquals("ok yes", s.text)
        assertEquals(listOf(SpeechMood.Cue(3, Mood.HAPPY)), s.cues)
    }

    @Test
    fun `cue offsets index the cleaned text`() {
        val s = SpeechMood.parse("[curious]这是什么。[happy]找到了。")
        assertEquals("这是什么。找到了。", s.text)
        assertEquals(
            listOf(SpeechMood.Cue(0, Mood.CURIOUS), SpeechMood.Cue(5, Mood.HAPPY)),
            s.cues,
        )
        assertEquals("找到了。", s.text.substring(5))
    }

    @Test
    fun `digit tags, shouted sentinels and markdown labels survive untouched`() {
        val s = SpeechMood.parse("有 [12条] 未读，[END] 见 [here](http://x)")
        assertEquals("有 [12条] 未读，[END] 见 [here](http://x)", s.text)
        assertEquals(emptyList<SpeechMood.Cue>(), s.cues)
    }

    /**
     * The bug this whole vocabulary exists for: asked to look angry, the model
     * wrote the obvious word, which was in neither the six names nor any face.
     */
    @Test
    fun `angry is a face rather than a word to read out`() {
        val s = SpeechMood.parse("[angry]你怎么又这样。")
        assertEquals("你怎么又这样。", s.text)
        assertEquals(listOf(SpeechMood.Cue(0, Mood.TENSE)), s.cues)
    }

    @Test
    fun `chinese names and lenticular brackets parse`() {
        val s = SpeechMood.parse("【生气】真是的。【开心】算了。")
        assertEquals("真是的。算了。", s.text)
        assertEquals(
            listOf(SpeechMood.Cue(0, Mood.TENSE), SpeechMood.Cue(4, Mood.HAPPY)),
            s.cues,
        )
    }

    /**
     * A lowercase bracketed word is a face attempt even when we don't know it.
     * Speaking it aloud is the one outcome that is never right; holding the
     * previous face is what a single-marker reply does anyway.
     */
    @Test
    fun `an unknown lowercase tag is dropped silently and names no face`() {
        val s = SpeechMood.parse("[happy]好了。[sparkly]你看。")
        assertEquals("好了。你看。", s.text)
        assertEquals(listOf(SpeechMood.Cue(0, Mood.HAPPY)), s.cues)
    }

    @Test
    fun `fullwidth brackets and mixed case parse`() {
        val s = SpeechMood.parse("［Calm］好的")
        assertEquals("好的", s.text)
        assertEquals(listOf(SpeechMood.Cue(0, Mood.CALM)), s.cues)
    }

    @Test
    fun `adjacent markers collapse to the later one`() {
        val s = SpeechMood.parse("[happy][concerned]出事了")
        assertEquals("出事了", s.text)
        assertEquals(listOf(SpeechMood.Cue(0, Mood.CONCERNED)), s.cues)
    }

    @Test
    fun `a reply that is only markers keeps its words rather than becoming silence`() {
        val s = SpeechMood.parse("[happy]")
        assertEquals("[happy]", s.text)
        assertEquals(emptyList<SpeechMood.Cue>(), s.cues)
    }

    @Test
    fun `a trailing marker does not report a cue past the end`() {
        val s = SpeechMood.parse("好的 [calm]")
        assertEquals("好的", s.text)
        assertEquals(listOf(SpeechMood.Cue(2, Mood.CALM)), s.cues)
        assertEquals(null, s.opening)
    }

    @Test
    fun `newlines belong to the text, not the marker`() {
        val s = SpeechMood.parse("第一句。\n[happy]第二句。")
        assertEquals("第一句。\n第二句。", s.text)
        assertEquals(listOf(SpeechMood.Cue(5, Mood.HAPPY)), s.cues)
    }
}
