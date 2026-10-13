package com.orthoflow.inventory.domain.model;

/**
 * What a stock quantity is actually counted in.
 *
 * <p>This replaces reading the free-text {@code unit} field and hoping: "1" against a
 * tube of composite meant nothing on its own — one gram, one millilitre, one tube? The
 * enum is what the system reasons about; {@code StockItem.unit} stays as the wording a
 * clinic prefers to see ("box of 100 pairs").
 *
 * <p>{@link #divisible} says whether a fraction of this unit is meaningful. You can use
 * 0.4 g of alginate; you cannot use 0.4 of a glove. It is what decides whether a
 * quantity may carry decimals, so the answer lives with the unit rather than being set
 * per item and drifting.
 */
public enum UnitOfMeasure {

    /** A countable thing: an instrument, a bracket, a bur. */
    PIECE("pc", false),
    PAIR("pair", false),
    BOX("box", false),
    PACK("pack", false),
    /** One anaesthetic carpule. */
    CARTRIDGE("cart", false),

    MILLIGRAM("mg", true),
    GRAM("g", true),

    MILLILITRE("ml", true),
    LITRE("L", true),

    MILLIMETRE("mm", true),
    CENTIMETRE("cm", true),
    METRE("m", true),

    /**
     * One application of something reusable — a polishing disc good for five polishes.
     * The item is still held in whole units; see {@code StockItem.usesPerUnit}.
     */
    USE("use", false);

    private final String symbol;
    private final boolean divisible;

    UnitOfMeasure(String symbol, boolean divisible) {
        this.symbol = symbol;
        this.divisible = divisible;
    }

    /** The short form shown beside a number. */
    public String symbol() {
        return symbol;
    }

    /** Whether a fraction of this unit means anything. */
    public boolean divisible() {
        return divisible;
    }
}
