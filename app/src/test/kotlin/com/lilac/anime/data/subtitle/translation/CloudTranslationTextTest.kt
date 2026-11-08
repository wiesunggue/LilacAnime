package com.lilac.anime.data.subtitle.translation

import com.lilac.anime.data.subtitle.translation.providers.CloudTranslationText
import org.junit.Assert.*
import org.junit.Test

class CloudTranslationTextTest {
    @Test fun missingSingleTranslationIsNeverDisplayedAsRawJson() {
        for (body in listOf("{\"lines\":[]}", "[{\"i\":2,\"t\":\"잘못된 번호\"}]", "```json\n{\"lines\":[{\"i\":1,\"t\":null}]}\n```", "[{\"i\":1,\"t\":\"\"}]", "[{\"i\":1")) {
            assertThrows(TranslationResponseException::class.java) { CloudTranslationText.parseMarked(body, listOf("Hello")) }
        }
    }
    @Test fun duplicateOrExtraCueNumbersAreRejected() {
        for (body in listOf("<LILAC_1> 안녕\n<LILAC_1> 덮어쓰기\n<LILAC_2> 다음", "<LILAC_1> 안녕\n<LILAC_2> 다음\n<LILAC_3> 추가", "[{\"i\":1,\"t\":\"안녕\"},{\"i\":1,\"t\":\"중복\"}]")) {
            assertThrows(TranslationResponseException::class.java) { CloudTranslationText.parseMarked(body, listOf("Hello", "Next")) }
        }
    }
    @Test fun reorderedJsonMapsToCueNumbersAndPlainSingleDialogueStillWorks() {
        assertEquals(listOf("안녕", "다음"), CloudTranslationText.parseMarked("{\"lines\":[{\"i\":2,\"t\":\"다음\"},{\"i\":1,\"t\":\"안녕\"}]}", listOf("Hello", "Next")))
        assertEquals(listOf("안녕하세요"), CloudTranslationText.parseMarked("안녕하세요", listOf("Hello")))
        assertEquals(listOf("[웃음]"), CloudTranslationText.parseMarked("[웃음]", listOf("[laughter]")))
    }
}
