import helium314.keyboard.latin.makedict.*;
import java.io.*;
public class BuildEmoji {
    public static void main(String[] a) throws Exception {
        FusionDictionary d;
        try (BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(a[0]), "UTF-8"))) {
            d = CombinedInputOutput.INSTANCE.readDictionaryCombined(r);
        }
        DictIO.INSTANCE.writeBinaryDictionary(a[1], d, FormatSpec.VERSION202);
    }
}
