package com.fc.v2.service;

import java.math.BigDecimal;
import java.util.Date;
import java.util.List;

import com.fc.v2.model.auto.TBarrTierLine;
import com.fc.v2.model.custom.TierVerdict;

/**
 * 入户评估定档线 Service接口（rule-eval 形状：规则求值，无增删改）。
 *
 * 全服务只认一套走法：某一刻该按哪条线归层、当口一条线也请不出时给不给说法、
 * 交班次序相同的两条听谁的、评估分正好压线时往哪一头落——这四问只由这里求值的那一处回话；
 * 接口不代判、屏面不预设答案、报表脚本不另拿口径。屏上那份在位名单与逐条翻线册凑出来的
 * 那份，出处同是 listAvailable 这一处。
 *
 * @author fuce
 * @date 2026-09-14
 */
public interface ITBarrTierLineService {

    /** 按主键查询规则（册里翻检用；已卸下的也翻得着，只是不许捞它定档） */
    TBarrTierLine selectTBarrTierLineById(Long id);

    /**
     * 单规则求值（按线代号取值的老方法：名目不动、参数不添、回话还是 0..4）。
     * 按 ruleCode 在 at 那一刻在位且合用的版本判档（1..4，压着分界数往高一档走）；
     * 补判往年那批户，比的是那一批户自己那一刻的线，不许拿眼下最新落的那条去套。
     * 那一刻请不出该代号的线 / 输入越出满分一百的尺 / 参数缺失，一律回 0，
     * 不许就着隔壁那条的数硬凑。
     */
    int evaluate(String ruleCode, BigDecimal input, Date at);

    /**
     * 多规则求值：at 那一刻可用线里"谁的话算数"排最前的那条判档（1..4）；
     * 那一刻一条可用线都请不出 / 输入越界 / 参数缺失，一律回 0。
     */
    int evaluateTop(BigDecimal input, Date at);

    /**
     * 定档结论一趟求值当场钉住：档、线代号、起用那一日同出这一趟；
     * 那一刻请不出可用线时结论栏落"无可用线"，线代号与起用那一日两栏一并留空。
     * 钉进户卡就钉这一趟回的这对，与求值当下回的那一对完全对得住。
     */
    TierVerdict evaluateDetail(BigDecimal input, Date at);

    /**
     * at 那一刻的可用线名单（交班次序降序、序同按线代号降序）：
     * 在册未删、未卸下、那一刻在位（起用那一日已经够着、交班那一日还没走到；
     * 交班一栏空着的老线一律按一直在位认）、三个分界数齐且后一格压过前一格。
     * 屏上名单与求值翻册同出这一处，不许另摆第二套。
     */
    List<TBarrTierLine> listAvailable(Date at);

    /**
     * 屏上还挂着线代号、册里那一刻却请不出可用线的单子（一条条指出来）：
     * 代号在册的行（含已卸下的）全在屏上，但那一刻该代号一条可用线都请不出，
     * 整个代号的行一并上单。已卸下的那条照列——它不参与现行比数，但屏上列得出。
     */
    List<TBarrTierLine> listOffDuty(Date at);

    /** 单条规则在 at 那一刻是否可用（批量导入/报表等旁路复用，口径与定位/列表同一处） */
    boolean usable(Long id, Date at);

    /** at 那一刻可用线条数（列表页角标 / 看板；与 listAvailable 同一出处） */
    int countAvailable(Date at);

    /**
     * 这条线三个分界数哪一格不合用：哪一格空着、又是哪一格没能压过它前头那一格；
     * 合用回 null。回话不许只丢一句"数不对劲"。
     */
    String thresholdProblem(TBarrTierLine line);

    /**
     * 按场景（这条线管公共点位还是居家适老）查那一刻的可用线——
     * 日后按点位查线走这条新立的口子，按线代号取值的老方法本体不动。
     */
    List<TBarrTierLine> listAvailableForScene(String sceneKind, Date at);

    /**
     * 按季查线：这一季里赶上过在位的可用线（季码如 2026Q4）——
     * 日后按季度查线走这条新立的口子，按线代号取值的老方法本体不动。
     */
    List<TBarrTierLine> listAvailableInSeason(String season);
}
