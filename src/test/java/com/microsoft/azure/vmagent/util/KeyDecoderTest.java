package com.microsoft.azure.vmagent.util;

import java.io.IOException;
import java.security.Security;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

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
            "NhAAAAAwEAAQAAAYEAmQfp/s+kY8kbjkqYlHQck/dwYlVVYyFB7HLVfU7nCkxGydTzrxvw\n" +
            "d8/ev5uvUsLQX4VCMfHRxCbM2zFHsBpyrszYJxzoeJB4e4oqWuKqHGxFASNZfkTIzubKHu\n" +
            "pHUE82IqyFsanZMf0vwX2wCr+K7BjAfVKirvZglJleboWT3G9ZIgMS2Ypz4hAfBSNPm/mS\n" +
            "XnPZmTG/ED0c3P6DK+X+SR7y7oLtXvrqeu8Hji0KOiU4eljQlH9TpGoP0088o11VPxLk9Y\n" +
            "T3yPSUuH2KIxA6zh0fczTQDeVsm4yAPTD4jdHh7Vuk8+TH2rh6VHH65lCVENZR29g0SP01\n" +
            "Tc6GjTldOTv6TCt5021ZptWYumBbyZjJz4nMrciQ12WpalVKsduRePdZjZvb75kevl9jgz\n" +
            "7SD4RZqzRP7jEeF++1ItG4ESeoRx/xq/Lt1oQLqAnsDC0LCvNQ9TIE1tEYvuY+kwiBlWLD\n" +
            "SPacnYQVF+NaeNB4CaZvAelhzKNcEuH2CkmMRgGFAAAFmGjbOl9o2zpfAAAAB3NzaC1yc2\n" +
            "EAAAGBAJkH6f7PpGPJG45KmJR0HJP3cGJVVWMhQexy1X1O5wpMRsnU868b8HfP3r+br1LC\n" +
            "0F+FQjHx0cQmzNsxR7Aacq7M2Ccc6HiQeHuKKlriqhxsRQEjWX5EyM7myh7qR1BPNiKshb\n" +
            "Gp2TH9L8F9sAq/iuwYwH1Soq72YJSZXm6Fk9xvWSIDEtmKc+IQHwUjT5v5kl5z2ZkxvxA9\n" +
            "HNz+gyvl/kke8u6C7V766nrvB44tCjolOHpY0JR/U6RqD9NPPKNdVT8S5PWE98j0lLh9ii\n" +
            "MQOs4dH3M00A3lbJuMgD0w+I3R4e1bpPPkx9q4elRx+uZQlRDWUdvYNEj9NU3Oho05XTk7\n" +
            "+kwredNtWabVmLpgW8mYyc+JzK3IkNdlqWpVSrHbkXj3WY2b2++ZHr5fY4M+0g+EWas0T+\n" +
            "4xHhfvtSLRuBEnqEcf8avy7daEC6gJ7AwtCwrzUPUyBNbRGL7mPpMIgZViw0j2nJ2EFRfj\n" +
            "WnjQeAmmbwHpYcyjXBLh9gpJjEYBhQAAAAMBAAEAAAGALb2s1oowI9dn0ic/5heytxOd1v\n" +
            "aUuDWno8pLP9JGwtA71HY/hFbAkL9kYDdjt0Qdzn9hYtZaEdxbHSVkvSGap974uPAuGGNu\n" +
            "b9bDhDj+CdLe6VEsnc5ni1h2j7kNKdcTYlfY+lq/Xe7EyHwOE5hfKOTZHFyH3e4svh39mO\n" +
            "F6acgqhz0N9FIrAyY4b2u3jvKRKoRMRTsWVf8+UlwMzyFG2YpTqLEfzGUJk0dBJeEWjnyz\n" +
            "nfZQGiLY2GsPYeTsRhROQWImusY5GwQwA0iSNuo0UU0aKFRT17SiVbZrcrc9J2xwnIuxhL\n" +
            "FwSTSsb5nF/xzspAG757vRpl1lCzN0toFh3eoP2U0ZYHcHF/ST56KNZ58UpI67IOPEsOBl\n" +
            "Y48REHmd8B5WINtAB5A9XJkcwvpD5yLy+3DIvSdU5kmXuq31b/N65np+IEu+0gHjkGFzYX\n" +
            "lvx/EXLFxIZVqyTYZsmxZeT1oBwApken/OEYHjKPJTjYq6Yorj/Zo0R5+ISz384ivBAAAA\n" +
            "wQC70iJSeck3wxahxaZfA6974WUFvA6Y2bjJbvFi2GYfwbH9rIGf6no3ovJXjwRUsCd7PL\n" +
            "vBxIB5vHOvRKV5F4lowLLbm4oX7l76mnjpSGAAcUB89pDat4FvCjsAs4ANqyrzosY1j/u/\n" +
            "81lZOf6Pv7MqXfm6/iUgiABRsO3RijkfrSp9HgwDjp0oU+8pyIJI0/0YwicbfYVQF4JMgV\n" +
            "mMTjsOtRDmQOhqNMU87cVY+FbEVNznfiuAndAD0UJT7n4JCHgAAADBAMZroxXUj9CalXTj\n" +
            "DC9c85afCNhxT1OCwftUuNdAKKiVXPpgYAT2I3n65V57bqFxEgZh9rqrhGLatqCCYVIPr+\n" +
            "83s6legZOVcl0EupjpsROtFXTtN3BZZfWVsAstyezIAz9quo5xC9ouf/rDjLXsDKI+y7pi\n" +
            "OMyMKLdpKAiVqTh2YQ0Eskv7k/VhQJ0CeiONNSfdmc1gfxUXhDd9qKRv/Xysg2ktPx7+IN\n" +
            "99uKE7ytnDMnn2ToD+CpDFRQ/FeXCvVQAAAMEAxXBa1fDIjoS+dskP1HYCfuTGaQS2c9XJ\n" +
            "czaFsA56u2jE3h6/4+3VBhJIrRBdFHfa9DT5gFfpsuRwoi7BX++9N0BNi2HqkoKBlstDW2\n" +
            "P3GBXcN8C/biRhsj3gkXMBR2nNeubN0aGlj533r0fr0KskcW3vS99Tkwa5REPSqbE3Uipz\n" +
            "rHbZqj/kpQY7GjpFjhMfe/Zm1I19ogGeWoWH48Mj9G3wmYPcHe4JorXWmiBEsU1J7DvpnQ\n" +
            "jCM2Bf3W9HpClxAAAAHnRpbWphQFRpbXMtTWFjQm9vay1Qcm8tMi5sb2NhbAECAwQ=\n" +
            "-----END OPENSSH PRIVATE KEY-----\n";

    @BeforeEach
    public void before() {
        // Add provider manually to avoid requiring jenkinsrule
        Security.addProvider(new org.bouncycastle.jce.provider.BouncyCastleProvider());
    }

    @Test
    void getRsaPublicKey() throws IOException {
        String actual = KeyDecoder.getRsaPublicKey(TEST_RSA_PEM_FORMAT, null);
        String expected = "ssh-rsa AAAAB3NzaC1yc2EAAAADAQABAAABgQCz9jecrCzqLuGVUayP/k3yzxm6P2uLyQ0Y9pFdI2GUT4G6GZQ70An6jVTufdLSMUL9Sb9xv0Hq/rWsO56vAEl5VJu1vqzmZ+EkfdsiFxiDLdYRhH47pnOYP4CEZmmYl5bO9cdYPvaa0WscRmGuZKm6vM/uniq2xxFEq/CWTTVXMCAvM8JDroP1VdeoPPaXBQROsxxxvw0AsU7PVcdT7YumC2iKeBb6KIePDNdw/6Xv71YQHSnNT9oM49xK8cx2M8j6WBmxHZSQo3fCHLALxdmfd9r/HA5l0WJeBZMM9S9EEd6gw+MaZJ1ChFVvhGYZqcLFqAYUem0UjyV7tdZCMwPQ004pjfkVrfHDQ6szSaKBFAKk2A8+RdGDg9XkKAbbUvam7ISoCu3qFLHSiBABtNmD1AN8huNhexVlCuvJAZKSEnJ0lpPSkFDZrzX1cG0cWuPvRXUpxmVBnSdAHM62uhFA7XuYOcvlUkLnqibIaDibFyvOLuD5xuEyTK4CXnB2r4E=";
        assertEquals(expected, actual);
    }

    @Test
    @Disabled("Need to implement a conversion to PEM format")
    // see
    // https://www.thedigitalcatonline.com/blog/2021/06/03/public-key-cryptography-openssh-private-keys/
    // and
    // https://serverfault.com/a/950686
    void getOpenSSHPublicKey() throws IOException {
        String actual = KeyDecoder.getRsaPublicKey(TEST_OPENSSH_FORMAT, null);
        String expected = "ssh-rsa AAAAB3NzaC1yc2EAAAADAQABAAABgQCZB+n+z6RjyRuOSpiUdByT93BiVVVjIUHsctV9TucKTEbJ1POvG/B3z96/m69SwtBfhUIx8dHEJszbMUewGnKuzNgnHOh4kHh7iipa4qocbEUBI1l+RMjO5soe6kdQTzYirIWxqdkx/S/BfbAKv4rsGMB9UqKu9mCUmV5uhZPcb1kiAxLZinPiEB8FI0+b+ZJec9mZMb8QPRzc/oMr5f5JHvLugu1e+up67weOLQo6JTh6WNCUf1Okag/TTzyjXVU/EuT1hPfI9JS4fYojEDrOHR9zNNAN5WybjIA9MPiN0eHtW6Tz5MfauHpUcfrmUJUQ1lHb2DRI/TVNzoaNOV05O/pMK3nTbVmm1Zi6YFvJmMnPicytyJDXZalqVUqx25F491mNm9vvmR6+X2ODPtIPhFmrNE/uMR4X77Ui0bgRJ6hHH/Gr8u3WhAuoCewMLQsK81D1MgTW0Ri+5j6TCIGVYsNI9pydhBUX41p40HgJpm8B6WHMo1wS4fYKSYxGAYU=";
        assertEquals(expected, actual);
    }
}