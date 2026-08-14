package com.tl2333.novelvoicereader.tts.service;

import android.os.Bundle;

interface ITtsInferenceService {
    Bundle initialize();
    Bundle synthesize(String requestId, String text, int voiceSid, float synthesisProfileSpeed, float silenceScale, String outputPath);
    void releaseEngine();
}
