package dev.localresume;

import org.springframework.stereotype.Component;
import org.springframework.web.util.HtmlUtils;
import java.util.regex.Pattern;

/** Intentionally small grammar: plain text plus **bold**. HTML and URLs are never executed. */
@Component("richText")
public class RichText {
    private static final Pattern BOLD = Pattern.compile("\\*\\*([^*\\n]+)\\*\\*");
    public String render(String input) {
        return BOLD.matcher(HtmlUtils.htmlEscape(input)).replaceAll("<strong>$1</strong>");
    }
}
