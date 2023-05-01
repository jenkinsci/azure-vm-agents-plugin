package com.microsoft.azure.vmagent.util;

import java.io.IOException;
import java.security.Security;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PemDecoderTest {

    private static final String TEST_RSA_PEM_FORMAT = "-----BEGIN RSA PRIVATE KEY-----\n" +
            "MIIG4wIBAAKCAYEAs/Y3nKws6i7hlVGsj/5N8s8Zuj9ri8kNGPaRXSNhlE+BuhmU\n" +
            "O9AJ+o1U7n3S0jFC/Um/cb9B6v61rDuerwBJeVSbtb6s5mfhJH3bIhcYgy3WEYR+\n" +
            "O6ZzmD+AhGZpmJeWzvXHWD72mtFrHEZhrmSpurzP7p4qtscRRKvwlk01VzAgLzPC\n" +
            "Q66D9VXXqDz2lwUETrMccb8NALFOz1XHU+2LpgtoingW+iiHjwzXcP+l7+9WEB0p\n" +
            "zU/aDOPcSvHMdjPI+lgZsR2UkKN3whywC8XZn3fa/xwOZdFiXgWTDPUvRBHeoMPj\n" +
            "GmSdQoRVb4RmGanCxagGFHptFI8le7XWQjMD0NNOKY35Fa3xw0OrM0migRQCpNgP\n" +
            "PkXRg4PV5CgG21L2puyEqArt6hSx0ogQAbTZg9QDfIbjYXsVZQrryQGSkhJydJaT\n" +
            "0pBQ2a819XBtHFrj70V1KcZlQZ0nQBzOtroRQO17mDnL5VJC56omyGg4mxcrzi7g\n" +
            "+cbhMkyuAl5wdq+BAgMBAAECggGAGMHutKcbJrx8XEZ4LvcVUiobp/vBl+F4485I\n" +
            "AUA01Gp7tlZ+hhwAw29eF9FHh5MvXBkhNUzSjLmt1Jv/IKQxd3ekVER/FNOFrbeC\n" +
            "xhXXUXSk1pQbqakkjfVugMh2DoAMRzyYyBMhafsVeTZVieBfWUlQcctgpPSN85yT\n" +
            "5JmKlQwR4WyFdTo0/TNqD8MTSYNvLESHzT4zU70Q0oVCftFXPOik4SfUQWYCADQM\n" +
            "D4ecFH4goObiMVSi8tMGI2lQb5KAAJ1/e7IVTJV7XJOpiCa0Zmkjp93qwnNQLkSv\n" +
            "Ij387kwEqa+jwAtAM6vUj+dJdS5g4UxU3GUmOeoGD89sUgDalOdFs261+JBMO75Z\n" +
            "lVvrIv8Ave1eVI2oYJDsrLSW9C5sts6whiFvoIvbyxu3UG4IRAwp5PEBLeZfLXDa\n" +
            "+JfWwmMi8cZ7uJGuCpCjRo/drZ1MJ4eH+DW9PVLDG5+NOALXqbbh58EpeURO4aWt\n" +
            "AQgZZMmUbeXG/t9fRbMk1giIHvJhAoHBANwDMYJxzlRlX+6ScC0X7rgZ348ts4kS\n" +
            "EAPqqftHLxYHUYmHgKGXpYP9rJIC2E/1GLXjyXh1kcFEM8WTQWglIuWXe7uQ6FBW\n" +
            "zkkdHSmRyqyheRn+09KnLI/4mdB0b0ywBU8SVuosR6wsUO/ncVYHnaO/lbB8L+2S\n" +
            "WT7pii3DEXKgzW9CukE2Pt385n6a+MxIB785VoHEnUNND0bb6eLJzRRUK5J7Lph7\n" +
            "o2yp99GY1ShU705iHTNcG1x14BDyF7IvcwKBwQDRZfDHug6B5i/YkKnXZ18Pamq2\n" +
            "h9W9rqo2/FGW31DsmDWz2TEwXhxYpcXVrHJnIGOnWx4mhkJAbpuRopPnFQRrEUVK\n" +
            "p53aSZm1XSWrhWa9N5lNkj1pAf8uVtdJ9HQZgCnBYZSBKiCezXOKgaiMLY4LkTGS\n" +
            "CSoOnLjcTh5UEN2Cp8hRJ9qA1/Omx38wU9uC3wTwHPF13xl0NF5vwE1UgTLIAxAB\n" +
            "HU1GFjijiA74781jrqquwfMeJNzcW53A4zMTQDsCgcBe382ox7TG9hRBR8qx12Es\n" +
            "6JNcJcQG9tALMFVus0qdwDgsC0+v3zoTyf9x02I+UJ4ASaMmm+RnqCZ/K7oH1APR\n" +
            "Z9qGjr1vb49zPefGdkUk3ljfmeD0NPzldLm3h447TqhraagrQT8DAvVADtjz1ULo\n" +
            "SwSc32C3nOV5WLIqe7T5blhIg0jYODEx/w3SSLfDN8iHcI1aVlPOottUnh6kmosi\n" +
            "Gg3UE+SLAr39bOod6z74LnAnp+2ZqT1vSLF//TW0s/sCgcEApoayiLPPi8Ca9lh9\n" +
            "qX9c2u6fdQ/rjJDWbyoUXK62NaIKuz+T3cpKMrmK7pXY2Wipt9M/1BVbpNARyi0I\n" +
            "AHL3c4pll6xbXdjAc7pjFdfeT8ZilY0iZ1seLCUFy5urpQgGrPLEXkA5dvEe3Nym\n" +
            "ROyJlWtfh6rgFoTOgu1hMyvCbYCvTtMp8uNKsqze2nkmDhr8W1Q5Nqs5G+/11luR\n" +
            "ZA3na6b72FcMOBu96VdvyMs0hPzIYnh9ttBMELaPa3GEEeA3AoHAaOMM2+fBwjOQ\n" +
            "jISP0wj3Cml8c7ij4bnCB3MJ+XIXLSiLRvCUUgr+s4hoRMGPJRbBKXCzqSRb/N7P\n" +
            "DNVekS1S4W96Zqikk99wZDVz4VpATo1EIJ3zIjyH6lWSC8Otp05BYjkMbYMQLKn1\n" +
            "JXN3hHZzmjWhP+jSD78M8G9Md6a/EFXbdv2XxL8VK1JZmcTn1IhQssEPNdRd25+6\n" +
            "t0seglGGOg6Sb1lyZ94fL1SeJAsQDpGh+wslUfjN1JsIEhm+9Vck\n" +
            "-----END RSA PRIVATE KEY-----";

    @BeforeEach
    public void before() {
        // Add provider manually to avoid requiring jenkinsrule
        Security.addProvider(new org.bouncycastle.jce.provider.BouncyCastleProvider());
    }

    @Test
    void getRsaPublicKey() throws IOException {
        String actual = PemDecoder.getRsaPublicKey(TEST_RSA_PEM_FORMAT, null);
        String expected = "ssh-rsa AAAAB3NzaC1yc2EAAAADAQABAAABgQCz9jecrCzqLuGVUayP/k3yzxm6P2uLyQ0Y9pFdI2GUT4G6GZQ70An6jVTufdLSMUL9Sb9xv0Hq/rWsO56vAEl5VJu1vqzmZ+EkfdsiFxiDLdYRhH47pnOYP4CEZmmYl5bO9cdYPvaa0WscRmGuZKm6vM/uniq2xxFEq/CWTTVXMCAvM8JDroP1VdeoPPaXBQROsxxxvw0AsU7PVcdT7YumC2iKeBb6KIePDNdw/6Xv71YQHSnNT9oM49xK8cx2M8j6WBmxHZSQo3fCHLALxdmfd9r/HA5l0WJeBZMM9S9EEd6gw+MaZJ1ChFVvhGYZqcLFqAYUem0UjyV7tdZCMwPQ004pjfkVrfHDQ6szSaKBFAKk2A8+RdGDg9XkKAbbUvam7ISoCu3qFLHSiBABtNmD1AN8huNhexVlCuvJAZKSEnJ0lpPSkFDZrzX1cG0cWuPvRXUpxmVBnSdAHM62uhFA7XuYOcvlUkLnqibIaDibFyvOLuD5xuEyTK4CXnB2r4E=";
        assertEquals(expected, actual);
    }
}