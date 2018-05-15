import okio.*;

import javax.swing.text.Segment;
import java.io.File;
import java.io.FileOutputStream;
import java.util.Map;
import java.util.Random;

public class Main {
    public static void main(String[] args) {
        File file = new File("env.txt");
        try {

            Sink fileSink = Okio.sink(file);
            BufferedSink bufferedSink = Okio.buffer(fileSink);
//
//            StringBuffer stringBuffer = new StringBuffer();
//            Random random = new Random();
//            int count = random.nextInt(9);
//            for (int i = 0; i < 2 * 8 * 1124; i++) {
//                stringBuffer.append(count);
//            }
//            bufferedSink.writeUtf8(stringBuffer.toString());



            for (Map.Entry<String, String> entry : System.getenv().entrySet()) {
                bufferedSink.writeUtf8(entry.getKey());
                bufferedSink.writeUtf8("=");
                bufferedSink.writeUtf8(entry.getValue());
                bufferedSink.writeUtf8("\n");
            }
            bufferedSink.flush();

            Source fileSource = Okio.source(file);
            BufferedSource bufferedSource = Okio.buffer(fileSource);
//            String fileLine = bufferedSource.readUtf8();
//            System.out.println(fileLine);
            for (String line; (line = bufferedSource.readUtf8Line()) != null; ) {
                System.out.println(line);
            }



        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
