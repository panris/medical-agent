package com.medicalagent.filter;

import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

/**
 * 输入脱敏过滤器 (T5.1)
 * 正则替换手机号、身份证号等敏感信息
 */
@Component
public class SensitiveDataFilter {

    // 手机号 (1xx-xxxx-xxxx)
    private static final Pattern PHONE_PATTERN = Pattern.compile(
            "1[3-9]\\d{1}[\\s-]?\\d{4}[\\s-]?\\d{4}"
    );

    // 身份证号 (18 位)
    private static final Pattern ID_CARD_PATTERN = Pattern.compile(
            "[1-9]\\d{5}(?:19|20)\\d{2}(?:0[1-9]|1[0-2])(?:0[1-9]|[12]\\d|3[01])\\d{3}[\\dXx]"
    );

    // 银行卡号 (16-19 位连续数字)
    private static final Pattern BANK_CARD_PATTERN = Pattern.compile(
            "\\b\\d{16,19}\\b"
    );

    // 姓名脱敏
    private static final Pattern NAME_PATTERN = Pattern.compile(
            "(?:我叫|我是|患者|姓名[是为：:]?\\s*)([\\u4e00-\\u9fa5]{2,4})"
    );

    /**
     * 脱敏处理
     */
    public String filter(String input) {
        if (input == null) return null;

        String result = input;

        // 手机号 → 138****1234
        result = PHONE_PATTERN.matcher(result).replaceAll(m -> {
            String phone = m.group().replaceAll("\\s|-", "");
            return phone.substring(0, 3) + "****" + phone.substring(7);
        });

        // 身份证 → 110***********1234
        result = ID_CARD_PATTERN.matcher(result).replaceAll(m -> {
            String id = m.group();
            return id.substring(0, 3) + "***********" + id.substring(14);
        });

        // 银行卡 → ****5678
        result = BANK_CARD_PATTERN.matcher(result).replaceAll(m -> {
            String card = m.group();
            return "****" + card.substring(card.length() - 4);
        });

        // 姓名 → 张* / 张**
        result = NAME_PATTERN.matcher(result).replaceAll(m -> {
            String name = m.group(1);
            if (name.length() <= 1) return name;
            return name.charAt(0) + "*".repeat(name.length() - 1);
        });

        return result;
    }

    /**
     * 日志用：返回脱敏摘要
     */
    public String filterForLog(String input) {
        return filter(input);
    }
}
