import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Created by siva on 8/15/17.
 */
public class SampleLogger {
    public static Logger LOGGER = LoggerFactory.getLogger(SampleLogger.class);

    public static void main(String args[]) {
        LOGGER.error("This is a sample log without arguments");
        String str = "India";
        LOGGER.error("This is logger with string arguments {}",str);
        int n = 42;
        LOGGER.error("This is logger with integer arguments {}", n);

        LOGGER.info("This will be matched : " + "John Smith");
        try {

        } catch (Exception e) {
            LOGGER.error("This is logger with exception ",e);
        }
    }
}
