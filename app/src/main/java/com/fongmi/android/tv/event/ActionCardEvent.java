package com.fongmi.android.tv.event;

import org.greenrobot.eventbus.EventBus;

/**
 * 实验室动作卡片内置指令事件（复制/刷新/源内搜索/跳转详情）。
 * 由 ActionCardHelper 在响应 spider 内置指令时发出，
 * 由同 source set 的 TypeFragment 订阅并执行（避免 main 模块反向依赖 leanback/mobile 的 VideoActivity）。
 */
public class ActionCardEvent {

    public enum Kind { REFRESH, SEARCH, PLAY }

    public final Kind kind;
    public final String keyword; // SEARCH 用：搜索关键字
    public final String url;     // PLAY 用：待播放地址

    /**
     * 消费标记：ViewPager 中同时存在多个 TypeFragment 都订阅了本事件，
     * 一次点击会被每个 fragment 各消费一次，导致 SEARCH/PLAY 重复拉起多个 Activity（返回要按多次）。
     * 这里约定：SEARCH/PLAY 仅由第一个收到事件的订阅者真正执行，执行后置 consumed=true，其余订阅者跳过。
     */
    public boolean consumed = false;

    private ActionCardEvent(Kind kind, String keyword, String url) {
        this.kind = kind;
        this.keyword = keyword;
        this.url = url;
    }

    public static void refresh() {
        EventBus.getDefault().post(new ActionCardEvent(Kind.REFRESH, null, null));
    }

    public static void search(String keyword) {
        EventBus.getDefault().post(new ActionCardEvent(Kind.SEARCH, keyword, null));
    }

    public static void play(String url) {
        EventBus.getDefault().post(new ActionCardEvent(Kind.PLAY, null, url));
    }
}
