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

        LOGGER.info("What is god? : " + n);

        LogHolder.LOGGER.info("From a different logger ..");

        LOGGER.info("This is an example for " + "append log " + getStr() + getMoreStr() + " and this should work!");
        try {

        } catch (Exception e) {
            LOGGER.error("This is logger with exception ",e);
        }
    }

    class Inner {
        private void log() {
            LOGGER.info("From Inner Class");
        }
    }

    static class StaticInner {
        private void log() {
            LOGGER.info("From Static Inner Class");
        }
    }

    private static String getStr() {
        return "with variables";
    }

    private static String getMoreStr() {
        return "with more variables";
    }
}
