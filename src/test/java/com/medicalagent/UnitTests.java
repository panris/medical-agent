package com.medicalagent;

import com.medicalagent.filter.SensitiveDataFilter;
import com.medicalagent.agent.RouterAgent;
import com.medicalagent.model.AgentState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Q-02: SensitiveDataFilter + RouterAgent 单元测试
 */
class UnitTests {

    private final SensitiveDataFilter filter = new SensitiveDataFilter();

    // === SensitiveDataFilter ===

    @Test
    @DisplayName("手机号脱敏")
    void testPhoneFilter() {
        assertEquals("138****1234", filter.filter("13812341234"));
    }

    @Test
    @DisplayName("手机号带空格脱敏")
    void testPhoneWithSpaces() {
        assertEquals("139****5432", filter.filter("139 9876 5432"));
    }

    @Test
    @DisplayName("身份证号脱敏")
    void testIdCardFilter() {
        // 身份证号 18 位，银行卡正则也可能匹配，测试脱敏结果不含明文
        String input = "身份证号110101199003076789";
        String result = filter.filter(input);
        assertFalse(result.contains("110101199003076789"));
        assertTrue(result.contains("****6789") || result.contains("110***********6789"));
    }

    @Test
    @DisplayName("银行卡号脱敏")
    void testBankCardFilter() {
        String input = "银行卡6222021234567890123";
        String result = filter.filter(input);
        assertTrue(result.contains("****0123"));
        assertFalse(result.contains("622202123456"));
    }

    @Test
    @DisplayName("姓名脱敏")
    void testNameFilter() {
        String result = filter.filter("我叫张三丰");
        // 名字被脱敏为 张* 或 张**（保留姓，隐藏名）
        assertTrue(result.contains("张") && !result.contains("张三丰"));
    }

    @Test
    @DisplayName("null 输入返回 null")
    void testNullInput() {
        assertNull(filter.filter(null));
    }

    @Test
    @DisplayName("无敏感信息的文本不变")
    void testNoSensitiveData() {
        String input = "头疼两天了，有点恶心";
        assertEquals(input, filter.filter(input));
    }

    @Test
    @DisplayName("组合脱敏：姓名+手机号")
    void testCombinedFilter() {
        String input = "我叫张三丰，手机号13812345678";
        String result = filter.filter(input);
        assertTrue(result.contains("张*"));
        assertTrue(result.contains("138****5678"));
        assertFalse(result.contains("张三丰"));
    }
}
