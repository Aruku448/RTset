package com.rtest.client.fsr;

/** Locks the native-library platform matrix without loading a platform-specific library. */
public final class NrdNativePlatformTest {
    private NrdNativePlatformTest() {
    }

    public static void main(String[] args) {
        assertSpec("Linux", "amd64", "linux-x86_64", "/rtest/natives/linux-x86_64/libprime_nrd.so",
                "libprime_nrd.so");
        assertSpec("Linux", "x86_64", "linux-x86_64", "/rtest/natives/linux-x86_64/libprime_nrd.so",
                "libprime_nrd.so");
        assertSpec("Windows 11", "amd64", "windows-x86_64",
                "/rtest/natives/windows-x86_64/prime_nrd.dll", "prime_nrd.dll");
        assertSpec("Windows 10", "x86-64", "windows-x86_64",
                "/rtest/natives/windows-x86_64/prime_nrd.dll", "prime_nrd.dll");
        if (NrdNative.librarySpec("Darwin", "amd64") != null
                || NrdNative.librarySpec("Windows 11", "aarch64") != null) {
            throw new AssertionError("unsupported NRD platform was accepted");
        }
        System.out.println("NRD native platform matrix passed");
    }

    private static void assertSpec(
            String os,
            String architecture,
            String platform,
            String resourcePath,
            String fileName) {
        NrdNative.LibrarySpec spec = NrdNative.librarySpec(os, architecture);
        if (spec == null
                || !platform.equals(spec.platform())
                || !resourcePath.equals(spec.resourcePath())
                || !fileName.equals(spec.fileName())) {
            throw new AssertionError("unexpected NRD library spec for " + os + "/" + architecture);
        }
    }
}
