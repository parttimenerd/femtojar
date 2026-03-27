import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

public class Before {
    static final Map<String, Integer> CODES;

    static {
        HashMap<String, Integer> tmp = new HashMap<>();
        tmp.put("ok", 0);
        tmp.put("err", 1);
        CODES = Collections.unmodifiableMap(tmp);
    }
}
