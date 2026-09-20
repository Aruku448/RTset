package com.rtest.client;

/** Contract test for explicit SDR/HDR output semantics and rebuild generations. */
public final class HdrFrameGraphPolicyTest {
    public static void main(String[] args) {
        var sdr = HdrFrameGraphPolicy.contract(HdrSupport.OutputMode.SDR, 1L);
        var hdr = HdrFrameGraphPolicy.contract(HdrSupport.OutputMode.HDR_SCRGB, 2L);
        if (sdr.sceneLinear() || !sdr.sRgbEncoded() || !sdr.toneMapRequired()) {
            throw new AssertionError("SDR contract is not explicit");
        }
        if (!hdr.sceneLinear() || hdr.sRgbEncoded() || hdr.toneMapRequired()) {
            throw new AssertionError("HDR scRGB contract is not explicit");
        }
        var rec2020 = HdrFrameGraphPolicy.contract(HdrSupport.OutputMode.HDR_BT2020_LINEAR, 3L);
        if (!rec2020.sceneLinear() || rec2020.sRgbEncoded() || rec2020.toneMapRequired()) {
            throw new AssertionError("HDR Rec.2020 contract is not explicit");
        }
        if (!HdrFrameGraphPolicy.requiresRebuild(sdr, hdr)
            || !HdrFrameGraphPolicy.requiresRebuild(hdr, rec2020)
            || HdrFrameGraphPolicy.requiresRebuild(hdr, hdr)) {
            throw new AssertionError("output mode rebuild policy is invalid");
        }
    }
}
