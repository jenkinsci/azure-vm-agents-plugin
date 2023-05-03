package com.microsoft.azure.vmagent.util;

import com.sshtools.common.publickey.InvalidPassphraseException;
import com.sshtools.common.ssh.SshException;
import java.io.IOException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class KeyDecoderTest {

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

    private static final String TEST_OPENSSH_FORMAT = "-----BEGIN OPENSSH PRIVATE KEY-----\n" +
            "b3BlbnNzaC1rZXktdjEAAAAABG5vbmUAAAAEbm9uZQAAAAAAAAABAAABlwAAAAdzc2gtcn\n" +
            "NhAAAAAwEAAQAAAYEA2UdV554IkUiMvJ5R//OxwgB5SVlnw4lhvrPGH55pXPSdDLRdHlj+\n" +
            "S1D6lb8FPU1k7qb/PThKGhfYZTZwJ1bTm2MgKK+HOj5oSrW0PmJbKDbB/XFG4kCp8At0fj\n" +
            "JgbERawLgdmDAq9i2vNoorB0dI5PMdcYVmzjAKYKIEjI+ktGnbPGaZxqVkfLucdumfxzS1\n" +
            "sv0suWqSuLMvhYujAGfVODx0VOFpSzlNzp8N+rZpO+hFODDIAeq/Mj8AZ83k+ipSrXUfS7\n" +
            "RFaU47eJPpiydqTokrPJfzcVLD8j9BIeI42EFCxjkR5JOqNqUTMheyhjHWSH+p7vVs3KE+\n" +
            "Zyn09rdtEQcPnrW8COGDPxbC0hzRUZSN0ziGx9BcOvoPGIIstkMLiHplU/gcjh/i7hT00I\n" +
            "bNP3BpWSOQOf3vPWOZClPUgswQdm7REXcvNYtWmLK58veUum0pc0Wb7qOj/f3/ImITOy80\n" +
            "Ddc4JFJ/bOzJNIyoq2iq7KYXJqx7UzPMyKSEO5uVAAAFmK9biEGvW4hBAAAAB3NzaC1yc2\n" +
            "EAAAGBANlHVeeeCJFIjLyeUf/zscIAeUlZZ8OJYb6zxh+eaVz0nQy0XR5Y/ktQ+pW/BT1N\n" +
            "ZO6m/z04ShoX2GU2cCdW05tjICivhzo+aEq1tD5iWyg2wf1xRuJAqfALdH4yYGxEWsC4HZ\n" +
            "gwKvYtrzaKKwdHSOTzHXGFZs4wCmCiBIyPpLRp2zxmmcalZHy7nHbpn8c0tbL9LLlqkriz\n" +
            "L4WLowBn1Tg8dFThaUs5Tc6fDfq2aTvoRTgwyAHqvzI/AGfN5PoqUq11H0u0RWlOO3iT6Y\n" +
            "snak6JKzyX83FSw/I/QSHiONhBQsY5EeSTqjalEzIXsoYx1kh/qe71bNyhPmcp9Pa3bREH\n" +
            "D561vAjhgz8WwtIc0VGUjdM4hsfQXDr6DxiCLLZDC4h6ZVP4HI4f4u4U9NCGzT9waVkjkD\n" +
            "n97z1jmQpT1ILMEHZu0RF3LzWLVpiyufL3lLptKXNFm+6jo/39/yJiEzsvNA3XOCRSf2zs\n" +
            "yTSMqKtoquymFyase1MzzMikhDublQAAAAMBAAEAAAGBAISrsz+fVpnnk8/kWCuSUNsl0O\n" +
            "lBx0M1UtLQEMzjvHA/CNpmE2nhazzv8GKZZgidhmDW5YkrIsw1/TMn/2l18fWynENbkpW0\n" +
            "35emxa1F/2VZsjAgB+lFFL73L6WS+x+AyW1dvuxblRAGqzMBQO7Lzy3FaRgVHcYOvXdt1p\n" +
            "tBZo+nB3AlMgaCnQ4wvIQ7eQ15GO12++UntvlCqGTB88DepeoVt+7QSKvfDKx6oF2THkSv\n" +
            "OfzqhGXvQdnbcFLx/LvoNL2iI30r4Sglr3U5e+5HqvV7rZp+bHXsKZ3oESek8dpd9zwX32\n" +
            "jf67IfngCU2wGO3MDS+at+pPbrm7E+tDv67LJN6dXgj8Eyly5ttXYBF4UByEPAXuDer8t2\n" +
            "Y8hPiUKYNNLQLfNqMGunJjVSPTMfZeX/zCAeu7Rb/tFFiI65K0stD5A97LkOhRRcJ/GZDX\n" +
            "uosVseKotSBwUVV7UhQuQEAHM1H0NsXhKMF0MAHLHQz1jqgZqmlnojfdvqdFWEo/7yoQAA\n" +
            "AMEA34y/Q2d6+NHhpvTqbtrubp3KmW+ZsLZKrnDGRMsI94JNb6OI3Rk2cAz2ZBxOuWNQtL\n" +
            "thg8bqTpYfPBbk7LtbPxcgGSuGuZSihAvZNn3Bl3Xe8k1r2wx1L06SEt3x02s8PT6aQ+1g\n" +
            "J7DY80ttJxfJw830bryDoo1DxKZ+pTsTR8bfz1haxGrsh12dvdhHyzPpMraGxzjut4PY+u\n" +
            "TBdQJnkxUwVNVlwwfbQr1wa3Rn65FZxzXH/tkj2sYqNtZNxpmVAAAAwQDsXPNSf/7vLglr\n" +
            "bFaHj7hYUDU27hC2JdjdS/u9yizSx6JD37lpSTJF/QNvVSjcGvnFrKpqQz9B9i5hfuDXAo\n" +
            "ZqWZutyiWFYHbl1rlesjwsiCkmkrTrnmT34rlt/4bkp58jhMP0KYKWexl4WHB0OhamiTSe\n" +
            "pmZNlRI/q6DSM16Qna2ZLSPVFtniQ3HUOWzv/DWXNLjADzS69FMXRyo28pmLhiClUEG/du\n" +
            "apH7hOOfYN85dxWYZDv7JBAToTjgGB+90AAADBAOtUfa8W3w1SYnxb4KCahW+OP7/rl6nK\n" +
            "QYchh4cmgGr9ArAYZAysY1ctZJTAZ1jElX2MVkTUdvIE+PK16ZwLDiPZaGBLXS7LpP55Mo\n" +
            "RO2npSfjCTJgIo0EHEBxVx/E2DWQeY+UGcaM/I9UP2co36JLewVe/yDLM56zrPd8kpS5Zg\n" +
            "wO8yow0ul/QqIAiRBx+Jy1Ea1fWhv1E86i73HIkT8+4z+HdDMwWEoH3/dzsndr6ict01T5\n" +
            "+EWY62VHL/bq9fGQAAAB50aW1qYUBUaW1zLU1hY0Jvb2stUHJvLTIubG9jYWwBAgM=\n" +
            "-----END OPENSSH PRIVATE KEY-----\n";

    @Test
    void getRsaPublicKey() throws IOException, InvalidPassphraseException, SshException {
        String actual = KeyDecoder.getPublicKey(TEST_RSA_PEM_FORMAT, null);
        String expected = "ssh-rsa AAAAB3NzaC1yc2EAAAADAQABAAABgQCz9jecrCzqLuGVUayP/k3yzxm6P2uLyQ0Y9pFdI2GUT4G6GZQ70An6jVTufdLSMUL9Sb9xv0Hq/rWsO56vAEl5VJu1vqzmZ+EkfdsiFxiDLdYRhH47pnOYP4CEZmmYl5bO9cdYPvaa0WscRmGuZKm6vM/uniq2xxFEq/CWTTVXMCAvM8JDroP1VdeoPPaXBQROsxxxvw0AsU7PVcdT7YumC2iKeBb6KIePDNdw/6Xv71YQHSnNT9oM49xK8cx2M8j6WBmxHZSQo3fCHLALxdmfd9r/HA5l0WJeBZMM9S9EEd6gw+MaZJ1ChFVvhGYZqcLFqAYUem0UjyV7tdZCMwPQ004pjfkVrfHDQ6szSaKBFAKk2A8+RdGDg9XkKAbbUvam7ISoCu3qFLHSiBABtNmD1AN8huNhexVlCuvJAZKSEnJ0lpPSkFDZrzX1cG0cWuPvRXUpxmVBnSdAHM62uhFA7XuYOcvlUkLnqibIaDibFyvOLuD5xuEyTK4CXnB2r4E=";
        assertEquals(expected, actual);
    }

    @Test
    void getOpenSSHPublicKey() throws IOException, InvalidPassphraseException, SshException {
        String actual = KeyDecoder.getPublicKey(TEST_OPENSSH_FORMAT, null);
        String expected = "ssh-rsa AAAAB3NzaC1yc2EAAAADAQABAAABgQDZR1XnngiRSIy8nlH/87HCAHlJWWfDiWG+s8Yfnmlc9J0MtF0eWP5LUPqVvwU9TWTupv89OEoaF9hlNnAnVtObYyAor4c6PmhKtbQ+YlsoNsH9cUbiQKnwC3R+MmBsRFrAuB2YMCr2La82iisHR0jk8x1xhWbOMApgogSMj6S0ads8ZpnGpWR8u5x26Z/HNLWy/Sy5apK4sy+Fi6MAZ9U4PHRU4WlLOU3Onw36tmk76EU4MMgB6r8yPwBnzeT6KlKtdR9LtEVpTjt4k+mLJ2pOiSs8l/NxUsPyP0Eh4jjYQULGORHkk6o2pRMyF7KGMdZIf6nu9WzcoT5nKfT2t20RBw+etbwI4YM/FsLSHNFRlI3TOIbH0Fw6+g8Ygiy2QwuIemVT+ByOH+LuFPTQhs0/cGlZI5A5/e89Y5kKU9SCzBB2btERdy81i1aYsrny95S6bSlzRZvuo6P9/f8iYhM7LzQN1zgkUn9s7Mk0jKiraKrsphcmrHtTM8zIpIQ7m5U=";
        assertEquals(expected, actual);
    }
}