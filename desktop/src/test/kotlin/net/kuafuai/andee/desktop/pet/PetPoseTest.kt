package net.kuafuai.andee.desktop.pet

import net.kuafuai.andee.pet.PetMind.emotionPose
import net.kuafuai.andee.ui.ball.Mood
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/** The pet is Andee's body: every face the model can pick lands on a drawing, and every new pose has a way back. */
class PetPoseTest {

    @Test fun everyEmotionHasADrawing() {
        assertEquals(PetPose.HAPPY, emotionPose(Mood.HAPPY))
        assertEquals(PetPose.ANGRY, emotionPose(Mood.TENSE))
        assertEquals(PetPose.SAD, emotionPose(Mood.CONCERNED))
        assertEquals(PetPose.CELEBRATE, emotionPose(Mood.CELEBRATE))
        assertEquals(PetPose.SPEAK, emotionPose(Mood.CALM))
        for (m in Mood.values()) assertNotNull(emotionPose(m))
    }

    @Test fun theBallsPosesFallBackToTheOriginalNine() {
        val original = setOf(PetPose.IDLE, PetPose.HAPPY, PetPose.AIR, PetPose.ANGRY, PetPose.SAD, PetPose.SLEEPY,
            PetPose.THINK, PetPose.TEA, PetPose.HOTPOT)
        for (p in PetPose.values()) if (p !in original && p != PetPose.HOLDING && p != PetPose.PEEK) {
            val f = p.fallback ?: throw AssertionError("$p has no fallback")
            assert(f in original) { "$p falls back to $f, which only girl may have" }
        }
    }

    @Test fun girlHasEveryPose() {
        for (p in PetPose.values()) {
            assertNotNull("girl-${p.file}.webp", javaClass.getResource("/pet/girl-${p.file}.webp"))
        }
    }
}
