package com.nnpg.alice.premium;

final class AutoSellLogic {
    private AutoSellLogic() {
    }

    static boolean shouldSell(AutoSell.FilterMode mode, boolean listed) {
        return mode == AutoSell.FilterMode.Whitelist ? listed : !listed;
    }
}
