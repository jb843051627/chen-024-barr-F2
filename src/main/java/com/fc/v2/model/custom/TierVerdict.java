package com.fc.v2.model.custom;

import java.util.Date;

/**
 * 定档结论：一趟求值当场钉住的那几栏。
 * 档、线代号、起用那一日同出这一趟求值，钉进户卡就钉这一趟回的这对，
 * 抄邻条的、抄上一季的都算错；那一刻请不出可用线时结论栏落"无可用线"，
 * 线代号与起用那一日两栏一并留空，不许就着隔壁那条的数硬凑一个体面答复。
 *
 * @author fuce
 * @date 2026-10-04
 */
public class TierVerdict {

    /** 结论栏：那一刻一条可用线都请不出时落这一句 */
    public static final String NO_LINE = "无可用线";

    /** 档：请不出可用线时落 0 */
    public static final int LEVEL_NONE = 0;

    /** 结论栏（请不出线落"无可用线"；请到了落"第N档"） */
    private String conclusion;

    /** 档 1..4；请不出可用线时 0 */
    private int level;

    /** 线代号（请不出可用线时留空） */
    private String ruleCode;

    /** 起用那一日（请不出可用线时留空） */
    private Date effStart;

    public TierVerdict() {
    }

    public TierVerdict(String conclusion, int level, String ruleCode, Date effStart) {
        this.conclusion = conclusion;
        this.level = level;
        this.ruleCode = ruleCode;
        this.effStart = effStart;
    }

    /** 请不出可用线的那一种回话：结论落"无可用线"，线代号与起用那一日两栏一并留空 */
    public static TierVerdict noLine() {
        return new TierVerdict(NO_LINE, LEVEL_NONE, null, null);
    }

    /** 这一趟有没有请到线：没请到就是"无可用线"，档落 0 */
    public boolean isNoLine() {
        return level == LEVEL_NONE;
    }

    public String getConclusion() {
        return conclusion;
    }

    public void setConclusion(String conclusion) {
        this.conclusion = conclusion;
    }

    public int getLevel() {
        return level;
    }

    public void setLevel(int level) {
        this.level = level;
    }

    public String getRuleCode() {
        return ruleCode;
    }

    public void setRuleCode(String ruleCode) {
        this.ruleCode = ruleCode;
    }

    public Date getEffStart() {
        return effStart;
    }

    public void setEffStart(Date effStart) {
        this.effStart = effStart;
    }
}
