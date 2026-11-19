package com.misaka.voicelog.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [TextDiff] 的判定边界。
 *
 * 这些用例存在的理由：整个「识别质量」面板的所有数字都从这一个函数出来。
 * 它把「删了个嗯」判成「同音字错误」，面板就会告诉你该换模型 —— 而真正的修法
 * 只是别输出填充词。分类错了，结论就会指错方向，而且看起来非常有说服力。
 */
class TextDiffTest {

    @Test
    fun `一字未动判为 same`() {
        val r = TextDiff.compare("今天天气很好", "今天天气很好")
        assertEquals(TextDiff.Kind.SAME, r.kind)
        assertEquals(0, r.total)
    }

    @Test
    fun `只动标点不算改字`() {
        // 用户重排标点很常见，那不是识别错误，不该进字错率
        val r = TextDiff.compare("你好，世界。", "你好世界")
        assertEquals(TextDiff.Kind.SAME, r.kind)
        assertEquals(0f, r.cer, 0.0001f)
    }

    @Test
    fun `删掉口水词不算错误`() {
        val r = TextDiff.compare("嗯我今天去上班", "我今天去上班")
        assertEquals(TextDiff.Kind.FILLER, r.kind)
        assertEquals(1, r.del)
        // 全量字错率会记这一笔，但「干净口径」必须归零 —— 否则整理句子会被误判成识别差
        assertTrue("全量 CER 应大于 0", r.cer > 0f)
        assertEquals(0f, r.cerClean, 0.0001f)
    }

    @Test
    fun `多个口水词仍判 filler`() {
        val r = TextDiff.compare("嗯那个我今天啊去上班", "我今天去上班")
        assertEquals(TextDiff.Kind.FILLER, r.kind)
        assertEquals(0f, r.cerClean, 0.0001f)
    }

    @Test
    fun `补字判 insert 且指向 VAD 或增益`() {
        val r = TextDiff.compare("今天气很好", "今天天气很好")
        assertEquals(TextDiff.Kind.INSERT, r.kind)
        assertEquals(1, r.ins)
        assertEquals(0, r.del)
        assertEquals(0, r.sub)
    }

    @Test
    fun `多字判 delete`() {
        val r = TextDiff.compare("今天天天天气很好", "今天天气很好")
        assertEquals(TextDiff.Kind.DELETE, r.kind)
        assertEquals(2, r.del)
    }

    @Test
    fun `同音字判 substitute`() {
        val r = TextDiff.compare("今天天起很好", "今天天气很好")
        assertEquals(TextDiff.Kind.SUBSTITUTE, r.kind)
        assertEquals(1, r.sub)
        // 同音错是模型上限问题，不该被「干净口径」洗掉 —— 它就是实打实的错
        assertTrue(r.cerClean > 0f)
    }

    @Test
    fun `增删改都有判 mixed`() {
        val r = TextDiff.compare("我吃了饭", "我喝了很多水")
        assertEquals(TextDiff.Kind.MIXED, r.kind)
        assertTrue(r.total > 0)
    }

    @Test
    fun `字错率分母是真值字数`() {
        // 原文 4 字，改后 6 字，错 2 个字 → 2/6
        val r = TextDiff.compare("今天天气", "今天天气很好")
        assertEquals(TextDiff.Kind.INSERT, r.kind)
        assertEquals(2f / 6f, r.cer, 0.0001f)
    }

    @Test
    fun `英文大小写与空格差异不算错`() {
        val r = TextDiff.compare("Hello World", "helloworld")
        assertEquals(TextDiff.Kind.SAME, r.kind)
    }

    @Test
    fun `超长文本降级不崩`() {
        val a = "今天天气很好".repeat(200)
        val b = "昨天天气不错".repeat(200)
        val r = TextDiff.compare(a, b)
        // 走降级分支，只要不抛异常且给出非零错误即可 —— 精度在这里不重要
        assertTrue(r.total > 0)
    }

    @Test
    fun `空串到有内容判 insert`() {
        val r = TextDiff.compare("", "我说话了")
        assertEquals(TextDiff.Kind.INSERT, r.kind)
        assertEquals(4, r.ins)
    }

    @Test
    fun `全文删除不会除零`() {
        val r = TextDiff.compare("我说话了", "")
        assertEquals(TextDiff.Kind.DELETE, r.kind)
        assertTrue(r.cer.isFinite())
    }

    @Test
    fun `归一化只保留有信息的字符`() {
        assertEquals("abc123", TextDiff.normalize("a b, c. 1-2-3!"))
        assertEquals("你好世界", TextDiff.normalize("你 好，世 界。"))
    }
}
