package com.example.smartnotetaker.transcription

import androidx.compose.ui.graphics.Color
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [34])
class ContinuousTranscriptTest {
    @Test fun preservesOriginalTextAndParagraphsWithoutTimestampGrouping() {
        val original = "Hello, world!\n\nAnother sentence."
        val text = ContinuousTranscript(AudioTranscript(original,"en",listOf(TimedCue(0,100,"Hello"), TimedCue(20000,20100,"world"))))
        assertEquals(original,text.text)
        assertEquals(original,text.highlighted(0,Color.Yellow,Color.Black).text)
        assertEquals(2,text.words.size)
    }
    @Test fun highlightingDoesNotAlterFontWeightOrTextWidthProperties() {
        val text = ContinuousTranscript(AudioTranscript("Hello world","en",listOf(TimedCue(0,100,"Hello"),TimedCue(100,200,"world"))))
        val first = text.highlighted(0,Color.Yellow,Color.Black)
        val second = text.highlighted(1,Color.Yellow,Color.Black)
        assertEquals(first.text,second.text)
        for (styled in listOf(first,second)) {
            assertEquals(1,styled.spanStyles.size)
            assertNull(styled.spanStyles.single().item.fontWeight)
            assertEquals(2,styled.getStringAnnotations("seek",0,styled.length).size)
        }
    }
    @Test fun repeatedWordsMapToTheirOwnAudioAndUnmatchedCuesDoNotRemoveText() {
        val text = ContinuousTranscript(AudioTranscript("Yes, yes! Keep all text.","en",listOf(TimedCue(0,100,"yes"),TimedCue(100,200,"yes"),TimedCue(200,300,"unmatched"))))
        assertEquals(listOf(0,5),text.words.map { it.start })
        assertEquals(listOf(0L,100L),text.words.map { it.startMs })
        assertEquals("Yes, yes! Keep all text.",text.highlighted(2,Color.Yellow,Color.Black).text)
    }
    @Test fun textOnlyResponsesStillDisplayCompleteText() {
        val text = ContinuousTranscript(AudioTranscript("No timing returned","en",emptyList()))
        assertTrue(text.words.isEmpty());assertEquals("No timing returned",text.highlighted(-1,Color.Yellow,Color.Black).text)
    }
    @Test fun tokenPricingUsesApproximateHourlySpeechCost() {
        val model = TranscriptionModel(JSONObject("""{"id":"test/model","priceUnit":"token","pricing":{"prompt":"0.000002","completion":"0.000012"}}"""))
        assertEquals("~$0.32/hour",model.price())
        val expensive = TranscriptionModel(JSONObject("""{"id":"test/expensive","priceUnit":"token","pricing":{"prompt":"0.0005","completion":"0.00041666666667"}}"""))
        assertEquals("~$50.00/hour",expensive.price())
    }
}
