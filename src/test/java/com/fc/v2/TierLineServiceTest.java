package com.fc.v2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fc.v2.mapper.auto.TBarrTierLineMapper;
import com.fc.v2.model.auto.TBarrTierLine;
import com.fc.v2.model.custom.TierVerdict;
import com.fc.v2.service.impl.TBarrTierLineServiceImpl;

/**
 * 入户评估定档线核心规矩单测（不起 Spring、不连库；mapper 全打桩）。
 * 四问——哪一刻按哪条、请不出给不给说法、序同听谁的、压线往哪头落——只认求值那一处。
 */
public class TierLineServiceTest {

    private TBarrTierLineServiceImpl service;
    private TBarrTierLineMapper lineMapper;

    /** 线册（打桩的库） */
    private final List<TBarrTierLine> book = new ArrayList<>();
    private final SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");

    @BeforeEach
    public void setUp() throws Exception {
        service = new TBarrTierLineServiceImpl();
        lineMapper = mock(TBarrTierLineMapper.class);
        setField(service, "barrTierLineMapper", lineMapper);
        book.clear();
        when(lineMapper.selectList(any())).thenAnswer(inv -> new ArrayList<>(book));
        when(lineMapper.selectById(any())).thenAnswer(inv -> {
            Object id = inv.getArgument(0);
            for (TBarrTierLine r : book) {
                if (r.getId() != null && r.getId().equals(id)) {
                    return r;
                }
            }
            return null;
        });
    }

    private void setField(Object target, String name, Object value) throws Exception {
        Field f = null;
        Class<?> c = target.getClass();
        while (c != null && f == null) {
            try {
                f = c.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
                c = c.getSuperclass();
            }
        }
        assertTrue(f != null, "字段 " + name + " 找不着");
        f.setAccessible(true);
        f.set(target, value);
    }

    private Date at(String text) throws Exception {
        return sdf.parse(text);
    }

    private BigDecimal bd(String text) {
        return new BigDecimal(text);
    }

    private TBarrTierLine line(long id, String code, String th1, String th2, String th3,
            String effStart, String effEnd, Integer priority, Integer status) throws Exception {
        TBarrTierLine r = new TBarrTierLine();
        r.setId(id);
        r.setRuleCode(code);
        r.setRuleName(code + "线");
        r.setTh1Max(th1 == null ? null : new BigDecimal(th1));
        r.setTh2Max(th2 == null ? null : new BigDecimal(th2));
        r.setTh3Max(th3 == null ? null : new BigDecimal(th3));
        r.setEffStart(effStart == null ? null : sdf.parse(effStart));
        r.setEffEnd(effEnd == null ? null : sdf.parse(effEnd));
        r.setPriority(priority);
        r.setStatus(status);
        r.setDelFlag(0);
        return r;
    }

    @Test
    public void 三个分界数缺一格_这条线用不了_回话讲清哪一格() throws Exception {
        TBarrTierLine noTh1 = line(1, "A", null, "60", "90", "2026-01-01 00:00:00", null, 1, 0);
        TBarrTierLine noTh2 = line(2, "B", "40", null, "90", "2026-01-01 00:00:00", null, 1, 0);
        TBarrTierLine noTh3 = line(3, "C", "40", "60", null, "2026-01-01 00:00:00", null, 1, 0);

        String p1 = service.thresholdProblem(noTh1);
        assertTrue(p1.contains("起算分") && p1.contains("空着"), "要点明起算分空着，实际：" + p1);
        String p2 = service.thresholdProblem(noTh2);
        assertTrue(p2.contains("够线分") && p2.contains("空着"), "要点明够线分空着，实际：" + p2);
        String p3 = service.thresholdProblem(noTh3);
        assertTrue(p3.contains("封顶分") && p3.contains("空着"), "要点明封顶分空着，实际：" + p3);
        assertNotNull(service.thresholdProblem(null));

        // 缺格的线不参与定档：名单没有它、单条问不可用、求值请不出线
        book.add(noTh1);
        Date at = at("2026-06-01 00:00:00");
        assertEquals(0, service.evaluateTop(bd("50"), at));
        assertFalse(service.usable(1L, at));
        assertEquals(0, service.countAvailable(at));

        // 合用的线回话为 null
        assertNull(service.thresholdProblem(line(9, "Z", "40", "60", "90", "2026-01-01 00:00:00", null, 1, 0)));
    }

    @Test
    public void 后一格没能压过前一格_这条线用不了_回话点明哪两格() throws Exception {
        TBarrTierLine flat = line(1, "A", "60", "60", "90", "2026-01-01 00:00:00", null, 1, 0);
        TBarrTierLine invert = line(2, "B", "40", "70", "70", "2026-01-01 00:00:00", null, 1, 0);

        String p1 = service.thresholdProblem(flat);
        assertTrue(p1.contains("够线分") && p1.contains("起算分") && p1.contains("没能压过"),
                "要点明够线分没能压过起算分，实际：" + p1);
        String p2 = service.thresholdProblem(invert);
        assertTrue(p2.contains("封顶分") && p2.contains("够线分") && p2.contains("没能压过"),
                "要点明封顶分没能压过够线分，实际：" + p2);

        // 立不住的线不参与定档，且被单子一条条指出来
        book.add(flat);
        book.add(invert);
        Date at = at("2026-06-01 00:00:00");
        assertEquals(0, service.evaluateTop(bd("50"), at));
        assertEquals(2, service.listOffDuty(at).size());
    }

    @Test
    public void 评估分先按同一把舍法取齐再比线() throws Exception {
        book.add(line(1, "A", "40", "70", "90", "2026-01-01 00:00:00", null, 1, 0));
        Date at = at("2026-06-01 00:00:00");
        // 69.995 取齐成 70.00，正好压着够线那个数：往高档走（第 3 档）；
        // 若取数与比数分两处各舍各的，这一户就被生生挪低一档
        assertEquals(3, service.evaluateTop(bd("69.995"), at));
        assertEquals(3, service.evaluate("A", bd("69.995"), at));
        assertEquals(2, service.evaluateTop(bd("69.994"), at));
        assertEquals(3, service.evaluateTop(bd("70.004"), at));
    }

    @Test
    public void 问话问的是那一刻_补判前年那批户不套今年新调的线() throws Exception {
        book.add(line(1, "X", "30", "60", "90", "2024-01-01 00:00:00", "2025-01-01 00:00:00", 1, 0));
        book.add(line(2, "X", "10", "20", "30", "2025-01-01 00:00:00", null, 1, 0));
        Date oldAt = at("2024-10-15 09:30:00");
        Date nowAt = at("2026-03-01 09:30:00");

        // 前年秋天那一刻：老线说话，50 落第 2 档；拿今年新线去套就凭空低成第 4 档——不留第二回
        assertEquals(2, service.evaluate("X", bd("50"), oldAt));
        assertEquals(2, service.evaluateTop(bd("50"), oldAt));
        // 眼下那一刻：新线说话
        assertEquals(4, service.evaluate("X", bd("50"), nowAt));
        // 钉住的一对也对得住前年那一刻
        TierVerdict old = service.evaluateDetail(bd("50"), oldAt);
        assertEquals("X", old.getRuleCode());
        assertEquals(sdf.parse("2024-01-01 00:00:00"), old.getEffStart());
    }

    @Test
    public void 新旧交替那一日_旧线管到之前新线从该日往后_问两回一字不差() throws Exception {
        book.add(line(1, "X", "30", "60", "90", "2024-01-01 00:00:00", "2025-01-01 00:00:00", 1, 0));
        book.add(line(2, "X", "10", "20", "30", "2025-01-01 00:00:00", null, 1, 0));

        Date before = at("2024-12-31 23:59:59");
        Date handover = at("2025-01-01 00:00:00");
        assertEquals(2, service.evaluateTop(bd("50"), before), "旧线管到该日之前为止");
        assertEquals(4, service.evaluateTop(bd("50"), handover), "新线从该日往后说话");
        // 同一刻各问一回，两回的回话一字不差
        assertEquals(service.evaluateTop(bd("50"), handover), service.evaluateTop(bd("50"), handover));
        assertEquals(service.evaluateDetail(bd("50"), handover).getEffStart(),
                service.evaluateDetail(bd("50"), handover).getEffStart());
    }

    @Test
    public void 在位凑出两条_交班次序高的先说_序同线代号后排的赢() throws Exception {
        book.add(line(10, "A", "30", "60", "90", "2026-01-01 00:00:00", null, 1, 0));
        book.add(line(11, "B", "5", "10", "15", "2026-01-01 00:00:00", null, 2, 0));
        Date at = at("2026-06-01 00:00:00");

        // 次序高的 B 先说：50>=15 落第 4 档（若 A 说话则是第 2 档）
        assertEquals(4, service.evaluateTop(bd("50"), at));
        assertEquals("B", service.evaluateDetail(bd("50"), at).getRuleCode());

        // 序一样高：线代号照排法排在后面的赢
        book.get(1).setPriority(1);
        assertEquals("B", service.evaluateDetail(bd("50"), at).getRuleCode());

        // 库里回行先后翻个个儿，回话不变——同一刻只容一个结论
        Collections.reverse(book);
        assertEquals("B", service.evaluateDetail(bd("50"), at).getRuleCode());
        assertEquals(4, service.evaluateTop(bd("50"), at));

        // 名单排法钉死：B 在前 A 在后
        List<TBarrTierLine> avail = service.listAvailable(at);
        assertEquals("B", avail.get(0).getRuleCode());
        assertEquals("A", avail.get(1).getRuleCode());
    }

    @Test
    public void 同代号凑出两条也只留一条说话() throws Exception {
        book.add(line(20, "X", "30", "60", "90", "2026-01-01 00:00:00", null, 1, 0));
        book.add(line(21, "X", "5", "10", "15", "2026-01-01 00:00:00", null, 1, 0));
        Date at = at("2026-06-01 00:00:00");
        // 同代号同次序：主键后到册的赢（21 那条，50>=15 → 第 4 档）
        assertEquals(4, service.evaluate("X", bd("50"), at));
        Collections.reverse(book);
        assertEquals(4, service.evaluate("X", bd("50"), at), "回行先后不许带偏结论");
    }

    @Test
    public void 一条可用线都请不出_结论落无可用线_两栏留空不硬凑() throws Exception {
        // 一条早已交班、一条还没起用：隔壁两条都在册，却一条都不许捞
        book.add(line(1, "OLD", "30", "60", "90", "2020-01-01 00:00:00", "2021-01-01 00:00:00", 1, 0));
        book.add(line(2, "FUT", "30", "60", "90", "2099-01-01 00:00:00", null, 1, 0));
        Date at = at("2026-06-01 00:00:00");

        assertEquals(0, service.evaluateTop(bd("50"), at));
        assertEquals(0, service.evaluate("OLD", bd("50"), at));
        TierVerdict v = service.evaluateDetail(bd("50"), at);
        assertEquals(TierVerdict.NO_LINE, v.getConclusion());
        assertEquals("无可用线", v.getConclusion());
        assertEquals(0, v.getLevel());
        assertTrue(v.isNoLine());
        assertNull(v.getRuleCode(), "线代号一栏留空");
        assertNull(v.getEffStart(), "起用那一日一栏留空");
        // 请不出线的两个代号都上单子
        assertEquals(2, service.listOffDuty(at).size());
    }

    @Test
    public void 屏上挂着代号那一刻请不出线的单子一条条指出来() throws Exception {
        Date at = at("2026-06-01 00:00:00");
        book.add(line(1, "A", "30", "60", "90", "2026-01-01 00:00:00", null, 1, 0));
        book.add(line(2, "B", "30", "60", "90", "2020-01-01 00:00:00", null, 1, 1));
        book.add(line(3, "C", "30", "60", "90", "2099-01-01 00:00:00", null, 1, 0));
        book.add(line(4, "D", "60", "60", "90", "2026-01-01 00:00:00", null, 1, 0));
        book.add(line(5, "E", "30", "60", "90", "2020-01-01 00:00:00", "2021-01-01 00:00:00", 1, 0));
        book.add(line(6, "E", "30", "60", "90", "2026-01-01 00:00:00", null, 1, 0));

        List<TBarrTierLine> off = service.listOffDuty(at);
        // B 已卸下、C 还没起用、D 分界数立不住：三个代号上单子；
        // A 在位、E 有版本顶班，都不上
        assertEquals(3, off.size());
        assertEquals("B", off.get(0).getRuleCode());
        assertEquals("C", off.get(1).getRuleCode());
        assertEquals("D", off.get(2).getRuleCode());
    }

    @Test
    public void 已卸下的那条不参与现行比数_册里翻得着屏上列得出() throws Exception {
        Date at = at("2026-06-01 00:00:00");
        book.add(line(1, "R", "5", "10", "15", "2020-01-01 00:00:00", null, 9, 1));

        assertNotNull(service.selectTBarrTierLineById(1L), "册里翻得着");
        assertFalse(service.usable(1L, at), "不许捞它定档");
        assertEquals(0, service.evaluateTop(bd("50"), at), "不参与现行比数");
        assertEquals(0, service.evaluate("R", bd("50"), at));
        TierVerdict v = service.evaluateDetail(bd("50"), at);
        assertTrue(v.isNoLine());
        assertNull(v.getRuleCode(), "不许让它缀在新结论后头");
        assertEquals(1, service.listOffDuty(at).size(), "屏上列得出");
    }

    @Test
    public void 评估分压着分界数往高档走_几个口子同一档() throws Exception {
        Date at = at("2026-06-01 00:00:00");
        book.add(line(1, "X", "40", "70", "90", "2026-01-01 00:00:00", null, 1, 0));

        // 压着够线那个数：往高档走（第 3 档，不是第 2 档）
        assertEquals(3, service.evaluateTop(bd("70"), at));
        // 压着起算：第 2 档；压着封顶：第 4 档；差一分不到起算：第 1 档
        assertEquals(2, service.evaluateTop(bd("40"), at));
        assertEquals(4, service.evaluateTop(bd("90"), at));
        assertEquals(1, service.evaluateTop(bd("39.99"), at));
        // 几个口子问它，答出来的必须是同一档
        assertEquals(service.evaluate("X", bd("70"), at), service.evaluateTop(bd("70"), at));
        assertEquals(service.evaluateTop(bd("70"), at), service.evaluateDetail(bd("70"), at).getLevel());
    }

    @Test
    public void 存量老线交班空着_一律按一直在位认() throws Exception {
        // 早年手抄进册：只填了起用那一日，次序、情形也空着
        book.add(line(1, "L", "40", "70", "90", "2020-01-01 00:00:00", null, null, null));
        Date at = at("2026-09-30 23:59:59");

        assertTrue(service.usable(1L, at));
        assertEquals(2, service.evaluateTop(bd("50"), at));
        TierVerdict v = service.evaluateDetail(bd("50"), at);
        assertEquals("L", v.getRuleCode());
        assertEquals(sdf.parse("2020-01-01 00:00:00"), v.getEffStart());
    }

    @Test
    public void 钉进户卡的线代号与起用那一日_与这一趟求值对得住() throws Exception {
        Date at = at("2026-06-01 00:00:00");
        book.add(line(1, "A", "30", "60", "90", "2026-01-01 00:00:00", null, 1, 0));
        book.add(line(2, "B", "5", "10", "15", "2026-02-01 00:00:00", null, 2, 0));

        TierVerdict v = service.evaluateDetail(bd("50"), at);
        // 钉住的一对 = 这一刻名单排头那条的线代号与起用那一日
        TBarrTierLine top = service.listAvailable(at).get(0);
        assertEquals(top.getRuleCode(), v.getRuleCode());
        assertEquals(top.getEffStart(), v.getEffStart());
        assertEquals("B", v.getRuleCode());
        assertEquals(sdf.parse("2026-02-01 00:00:00"), v.getEffStart());
        // 抄邻条的算错：档也必须出自 B 这条线（50>=15 → 第 4 档），不是 A 的第 2 档
        assertEquals(4, v.getLevel());
        // 按钉住的代号回头问老方法，档一字不差
        assertEquals(v.getLevel(), service.evaluate(v.getRuleCode(), bd("50"), at));
    }

    @Test
    public void 越界缺参一律不回档() throws Exception {
        Date at = at("2026-06-01 00:00:00");
        book.add(line(1, "A", "40", "70", "90", "2026-01-01 00:00:00", null, 1, 0));

        assertEquals(0, service.evaluateTop(bd("-1"), at));
        assertEquals(0, service.evaluateTop(bd("100.005"), at), "取齐后 100.01，越出满分一百的尺");
        assertEquals(4, service.evaluateTop(bd("100"), at), "满分压着封顶：第 4 档");
        assertEquals(0, service.evaluateTop(null, at));
        assertEquals(0, service.evaluateTop(bd("50"), null));
        assertEquals(0, service.evaluate(null, bd("50"), at));
        assertEquals(0, service.evaluate(" ", bd("50"), at));
        assertEquals(0, service.evaluate("A", null, at));
        assertEquals(0, service.evaluate("A", bd("-3"), at));
        assertTrue(service.evaluateDetail(null, at).isNoLine());
        assertTrue(service.evaluateDetail(bd("50"), null).isNoLine());
        assertEquals(0, service.countAvailable(null));
        assertFalse(service.usable(null, at));
        assertFalse(service.usable(1L, null));
        assertTrue(service.listAvailable(null).isEmpty());
        assertTrue(service.listOffDuty(null).isEmpty());
    }

    @Test
    public void 按场景按季查线走新口子_老方法还是老样() throws Exception {
        Date at = at("2026-06-01 00:00:00");
        TBarrTierLine pub = line(1, "PUB", "40", "70", "90", "2026-01-01 00:00:00", null, 1, 0);
        pub.setSceneKind("公共点位");
        TBarrTierLine home = line(2, "HOME", "40", "70", "90", "2026-01-01 00:00:00", null, 1, 0);
        home.setSceneKind("居家适老");
        book.add(pub);
        book.add(home);

        assertEquals(2, service.listAvailableForScene(null, at).size());
        assertEquals(2, service.listAvailableForScene(" ", at).size());
        List<TBarrTierLine> pubs = service.listAvailableForScene("公共点位", at);
        assertEquals(1, pubs.size());
        assertEquals("PUB", pubs.get(0).getRuleCode());
        assertEquals(1, service.listAvailableForScene("居家适老", at).size());
        assertEquals(0, service.listAvailableForScene("不存在的场景", at).size());

        // 按季查线：这一季里赶上过在位的都查得着
        book.clear();
        book.add(line(1, "Q3", "40", "70", "90", "2026-07-01 00:00:00", "2026-10-01 00:00:00", 1, 0));
        book.add(line(2, "Q4", "40", "70", "90", "2026-10-01 00:00:00", "2027-01-01 00:00:00", 1, 0));
        book.add(line(3, "OLD", "40", "70", "90", "2020-01-01 00:00:00", null, 1, 0));
        assertEquals(2, service.listAvailableInSeason("2026Q3").size(), "Q3 那条加一直在位的老线");
        assertEquals(2, service.listAvailableInSeason("2026Q4").size(), "Q4 那条加一直在位的老线");
        assertTrue(service.listAvailableInSeason("2026Q5").isEmpty(), "季码不像样：空，不报错");
        assertTrue(service.listAvailableInSeason(null).isEmpty());

        // 老方法还是老样：名目没动、回话还是 0..4
        assertEquals(1, service.evaluate("Q4", bd("10"), at("2026-11-01 00:00:00")));
    }

    @Test
    public void 屏上名单与逐条翻册同一出处_计数与单条口径一致() throws Exception {
        Date at = at("2026-06-01 00:00:00");
        book.add(line(1, "A", "30", "60", "90", "2026-01-01 00:00:00", null, 1, 0));
        book.add(line(2, "B", "5", "10", "15", "2026-01-01 00:00:00", null, 2, 0));
        book.add(line(3, "C", "30", "60", "90", "2020-01-01 00:00:00", null, 3, 1));
        book.add(line(4, "D", "30", "60", "90", "2099-01-01 00:00:00", null, 4, 0));
        book.add(line(5, "E", "60", "60", "90", "2026-01-01 00:00:00", null, 5, 0));

        List<TBarrTierLine> avail = service.listAvailable(at);
        assertEquals(2, avail.size());
        assertEquals("B", avail.get(0).getRuleCode());
        assertEquals("A", avail.get(1).getRuleCode());
        // 计数与名单同一出处
        assertEquals(avail.size(), service.countAvailable(at));
        // 单条口径与名单一致：名单里的皆可用，名单外的皆不可用
        for (TBarrTierLine r : book) {
            assertEquals(avail.contains(r), service.usable(r.getId(), at), r.getRuleCode());
        }
        // 求值钉的那条就是名单排头那条
        assertEquals(avail.get(0).getRuleCode(), service.evaluateDetail(bd("50"), at).getRuleCode());
        // 名单翻两回一个样
        assertEquals(avail.size(), service.listAvailable(at).size());
    }
}
