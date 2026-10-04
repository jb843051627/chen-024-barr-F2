package com.fc.v2.service.impl;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fc.v2.mapper.auto.TBarrTierLineMapper;
import com.fc.v2.model.auto.TBarrTierLine;
import com.fc.v2.model.custom.TierVerdict;
import com.fc.v2.service.ITBarrTierLineService;

/**
 * 入户评估定档线 Service业务层处理（rule-eval 形状：规则求值，无增删改）。
 *
 * 全服务只此一套走法：某一刻该按哪条线归层、一条都请不出时给不给说法、
 * 交班次序相同的两条听谁的、评估分正好压线往哪一头落——四问只由这里回话，
 * 接口不代判、屏面不预设、报表不另拿口径。屏上那份在位名单与逐条翻册凑出来的
 * 那份同出 listAvailable 这一处；评估分先按同一把舍法取齐再比线，取数与比数
 * 不许再分两处各舍各的。
 *
 * @author fuce
 * @date 2026-09-14
 */
@Service
public class TBarrTierLineServiceImpl implements ITBarrTierLineService {

    /** 评估分的尺：满分一百，越出 [0,100] 的分一律不回档 */
    private static final BigDecimal SCORE_FLOOR = new BigDecimal("0");
    private static final BigDecimal SCORE_CEIL = new BigDecimal("100");

    /** 线的情形：已卸下（册里翻得着、屏上列得出，只是不许捞它定档） */
    private static final int STATUS_RETIRED = 1;

    /** 季码，如 2026Q4 */
    private static final Pattern SEASON = Pattern.compile("^(\\d{4})[Qq]([1-4])$");

    /**
     * 谁的话算数：交班次序高的先说；序一样高比线代号，照排法排在后面的赢；
     * 再齐按主键后到册的赢。排法带兜底、与库里回行先后无关——
     * 同一刻只容一个结论，问两回回话一字不差。
     */
    private static final Comparator<TBarrTierLine> SPEAKING_ORDER = new Comparator<TBarrTierLine>() {
        @Override
        public int compare(TBarrTierLine a, TBarrTierLine b) {
            int pa = a.getPriority() == null ? 0 : a.getPriority();
            int pb = b.getPriority() == null ? 0 : b.getPriority();
            if (pa != pb) {
                return Integer.compare(pb, pa);
            }
            String ca = a.getRuleCode() == null ? "" : a.getRuleCode();
            String cb = b.getRuleCode() == null ? "" : b.getRuleCode();
            int byCode = cb.compareTo(ca);
            if (byCode != 0) {
                return byCode;
            }
            long ia = a.getId() == null ? 0L : a.getId();
            long ib = b.getId() == null ? 0L : b.getId();
            return Long.compare(ib, ia);
        }
    };

    @javax.annotation.Resource
    private TBarrTierLineMapper barrTierLineMapper;

    // ============================ 翻检 ============================

    @Override
    public TBarrTierLine selectTBarrTierLineById(Long id) {
        return this.barrTierLineMapper.selectById(id);
    }

    // ============================ 求值（四问同一出处） ============================

    @Override
    public int evaluate(String ruleCode, BigDecimal input, Date at) {
        // 老方法：名目不动、参数不添、回话还是 0..4；里子与求值那条路同一套口径
        if (ruleCode == null || ruleCode.trim().isEmpty() || input == null || at == null) {
            return 0;
        }
        BigDecimal score = align(input);
        if (outOfScale(score)) {
            return 0;
        }
        TBarrTierLine line = locate(ruleCode.trim(), at);
        return line == null ? 0 : levelOf(line, score);
    }

    @Override
    public int evaluateTop(BigDecimal input, Date at) {
        return evaluateDetail(input, at).getLevel();
    }

    @Override
    public TierVerdict evaluateDetail(BigDecimal input, Date at) {
        if (input == null || at == null) {
            return TierVerdict.noLine();
        }
        BigDecimal score = align(input);
        if (outOfScale(score)) {
            return TierVerdict.noLine();
        }
        List<TBarrTierLine> avail = listAvailable(at);
        if (avail.isEmpty()) {
            // 一条都请不出时不给档：结论落"无可用线"，两栏留空，不就着隔壁那条硬凑
            return TierVerdict.noLine();
        }
        TBarrTierLine line = avail.get(0);
        int level = levelOf(line, score);
        // 钉进户卡的就这一对：这一趟求值当下回的线代号与起用那一日
        return new TierVerdict("第" + level + "档", level, line.getRuleCode(), line.getEffStart());
    }

    // ============================ 名单 ============================

    @Override
    public List<TBarrTierLine> listAvailable(Date at) {
        List<TBarrTierLine> out = new ArrayList<>();
        if (at == null) {
            return out;
        }
        for (TBarrTierLine r : allOnBook()) {
            if (usableAt(r, at)) {
                out.add(r);
            }
        }
        out.sort(SPEAKING_ORDER);
        return out;
    }

    @Override
    public List<TBarrTierLine> listOffDuty(Date at) {
        List<TBarrTierLine> out = new ArrayList<>();
        if (at == null) {
            return out;
        }
        // 屏上挂着的代号先归拢：某代号那一刻一条可用线都请不出，整代号的行一并上单子
        Map<String, List<TBarrTierLine>> byCode = new LinkedHashMap<>();
        for (TBarrTierLine r : allOnBook()) {
            String code = r.getRuleCode() == null ? "" : r.getRuleCode();
            byCode.computeIfAbsent(code, k -> new ArrayList<TBarrTierLine>()).add(r);
        }
        for (List<TBarrTierLine> rows : byCode.values()) {
            boolean speaks = false;
            for (TBarrTierLine r : rows) {
                if (usableAt(r, at)) {
                    speaks = true;
                    break;
                }
            }
            if (!speaks) {
                out.addAll(rows);
            }
        }
        // 单子排法钉死：线代号升序、同号按主键升序，问两回一个样
        out.sort(new Comparator<TBarrTierLine>() {
            @Override
            public int compare(TBarrTierLine a, TBarrTierLine b) {
                String ca = a.getRuleCode() == null ? "" : a.getRuleCode();
                String cb = b.getRuleCode() == null ? "" : b.getRuleCode();
                int byCode = ca.compareTo(cb);
                if (byCode != 0) {
                    return byCode;
                }
                long ia = a.getId() == null ? 0L : a.getId();
                long ib = b.getId() == null ? 0L : b.getId();
                return Long.compare(ia, ib);
            }
        });
        return out;
    }

    @Override
    public boolean usable(Long id, Date at) {
        if (id == null || at == null) {
            return false;
        }
        TBarrTierLine r = this.barrTierLineMapper.selectById(id);
        return r != null && usableAt(r, at);
    }

    @Override
    public int countAvailable(Date at) {
        // 与名单同一出处，不许另拿一套口径去数
        return listAvailable(at).size();
    }

    @Override
    public String thresholdProblem(TBarrTierLine line) {
        if (line == null) {
            return "整条线空着，没法核";
        }
        if (line.getTh1Max() == null) {
            return "起算分：空着，这条线用不了";
        }
        if (line.getTh2Max() == null) {
            return "够线分：空着，这条线用不了";
        }
        if (line.getTh3Max() == null) {
            return "封顶分：空着，这条线用不了";
        }
        if (line.getTh2Max().compareTo(line.getTh1Max()) <= 0) {
            return "够线分：没能压过起算分（够线" + line.getTh2Max() + "、起算" + line.getTh1Max()
                    + "），这条线用不了";
        }
        if (line.getTh3Max().compareTo(line.getTh2Max()) <= 0) {
            return "封顶分：没能压过够线分（封顶" + line.getTh3Max() + "、够线" + line.getTh2Max()
                    + "），这条线用不了";
        }
        return null;
    }

    // ============================ 新立的口子（老方法本体不动） ============================

    @Override
    public List<TBarrTierLine> listAvailableForScene(String sceneKind, Date at) {
        List<TBarrTierLine> out = new ArrayList<>();
        String scene = sceneKind == null ? "" : sceneKind.trim();
        for (TBarrTierLine r : listAvailable(at)) {
            if (scene.isEmpty() || scene.equals(r.getSceneKind())) {
                out.add(r);
            }
        }
        return out;
    }

    @Override
    public List<TBarrTierLine> listAvailableInSeason(String season) {
        List<TBarrTierLine> out = new ArrayList<>();
        if (season == null) {
            return out;
        }
        Matcher m = SEASON.matcher(season.trim());
        if (!m.matches()) {
            return out;
        }
        int year = Integer.parseInt(m.group(1));
        int quarter = Integer.parseInt(m.group(2));
        LocalDate qStart = LocalDate.of(year, (quarter - 1) * 3 + 1, 1);
        LocalDate qEnd = qStart.plusMonths(3);
        Date start = java.sql.Timestamp.valueOf(qStart.atStartOfDay());
        Date end = java.sql.Timestamp.valueOf(qEnd.atStartOfDay());
        for (TBarrTierLine r : allOnBook()) {
            // 这一季里赶上过在位：起用那一日赶在季尾前、交班那一日（空着按一直在位）拖过季头
            boolean windowHit = (r.getEffStart() == null || r.getEffStart().before(end))
                    && (r.getEffEnd() == null || r.getEffEnd().after(start));
            if (windowHit && lineSound(r)) {
                out.add(r);
            }
        }
        out.sort(SPEAKING_ORDER);
        return out;
    }

    // ============================ 内部：口径只此一处 ============================

    /**
     * 同一把舍法：评估分先取齐（两位、四舍五入）再拿取齐的数去比线。
     * 取数与比数不许分两处各舍各的——去年那一户被生生挪低一档，差就差在这儿。
     */
    private static BigDecimal align(BigDecimal input) {
        return input.setScale(2, RoundingMode.HALF_UP);
    }

    /** 越出满分一百的尺（取齐之后再看），越界一律不回档 */
    private static boolean outOfScale(BigDecimal alignedScore) {
        return alignedScore.compareTo(SCORE_FLOOR) < 0 || alignedScore.compareTo(SCORE_CEIL) > 0;
    }

    /** 册里未删的行（屏上挂着的全在这；删了的谁也见不着） */
    private List<TBarrTierLine> allOnBook() {
        List<TBarrTierLine> rows = this.barrTierLineMapper
                .selectList(new QueryWrapper<TBarrTierLine>().eq("del_flag", 0));
        List<TBarrTierLine> out = new ArrayList<>();
        for (TBarrTierLine r : rows) {
            if (onBook(r)) {
                out.add(r);
            }
        }
        return out;
    }

    private static boolean onBook(TBarrTierLine r) {
        return r.getDelFlag() == null || r.getDelFlag() == 0;
    }

    /**
     * 那一刻在不在位：起用那一日已经够着、交班那一日还没走到（交班当日不含，
     * 旧线管到该日之前为止、新线从该日往后说话）；早年手抄进册的老线交班一栏空着，
     * 一律按一直在位认。时段比较全在内存里走 java.util.Date，不把 Date 绑给 JDBC——
     * serverTimezone 与本机时区不对称，曾把"切换当日"的判定整体带偏。
     */
    private static boolean inPosition(TBarrTierLine r, Date at) {
        if (r.getEffStart() != null && r.getEffStart().after(at)) {
            return false;
        }
        if (r.getEffEnd() != null && !r.getEffEnd().after(at)) {
            return false;
        }
        return true;
    }

    /** 这条线本身立不立得住：在册、未卸下、三个分界数齐且一格压过一格（不涉时刻） */
    private boolean lineSound(TBarrTierLine r) {
        return onBook(r)
                && (r.getStatus() == null || r.getStatus() != STATUS_RETIRED)
                && thresholdProblem(r) == null;
    }

    /** 这条线那一刻能不能说话：本身立得住，且那一刻在位 */
    private boolean usableAt(TBarrTierLine r, Date at) {
        return lineSound(r) && inPosition(r, at);
    }

    /** 单代号那一刻的在位线：同代号凑出多条也按同一套先后只留一条说话 */
    private TBarrTierLine locate(String ruleCode, Date at) {
        List<TBarrTierLine> hit = new ArrayList<>();
        for (TBarrTierLine r : allOnBook()) {
            if (ruleCode.equals(r.getRuleCode()) && usableAt(r, at)) {
                hit.add(r);
            }
        }
        if (hit.isEmpty()) {
            return null;
        }
        hit.sort(SPEAKING_ORDER);
        return hit.get(0);
    }

    /** 分档：压着分界数的那一户往高一档走（够线那个数也算够着高一档） */
    private static int levelOf(TBarrTierLine line, BigDecimal alignedScore) {
        if (alignedScore.compareTo(line.getTh1Max()) < 0) {
            return 1;
        }
        if (alignedScore.compareTo(line.getTh2Max()) < 0) {
            return 2;
        }
        if (alignedScore.compareTo(line.getTh3Max()) < 0) {
            return 3;
        }
        return 4;
    }
}
