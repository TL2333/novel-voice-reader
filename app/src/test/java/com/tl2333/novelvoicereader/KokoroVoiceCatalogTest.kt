package com.tl2333.novelvoicereader

import com.google.common.truth.Truth.assertThat
import com.tl2333.novelvoicereader.tts.kokoro.KokoroVoiceCatalog
import com.tl2333.novelvoicereader.tts.kokoro.VoiceGender
import org.junit.Test

class KokoroVoiceCatalogTest {
    @Test
    fun exposesExactOfficialChineseSpeakerRangesAndDefaults() {
        assertThat(KokoroVoiceCatalog.voices).hasSize(100)
        assertThat(KokoroVoiceCatalog.femaleVoices).hasSize(55)
        assertThat(KokoroVoiceCatalog.maleVoices).hasSize(45)
        assertThat(KokoroVoiceCatalog.voices.map { it.sid }).containsExactlyElementsIn(3..102).inOrder()

        assertThat(KokoroVoiceCatalog.defaultFemale.internalName).isEqualTo("zf_001")
        assertThat(KokoroVoiceCatalog.defaultFemale.displayName).isEqualTo("中文女声 01")
        assertThat(KokoroVoiceCatalog.defaultMale.internalName).isEqualTo("zm_009")
        assertThat(KokoroVoiceCatalog.defaultMale.displayName).isEqualTo("中文男声 09")
        assertThat(KokoroVoiceCatalog.defaultMale.gender).isEqualTo(VoiceGender.MALE)
        assertThat(KokoroVoiceCatalog.requireBySid(102).internalName).isEqualTo("zm_100")
    }
}
