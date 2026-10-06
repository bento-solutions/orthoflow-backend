package com.orthoflow.sterilization.application.service;

import com.orthoflow.sterilization.application.dto.SterilizationDtos.Action;
import com.orthoflow.sterilization.application.dto.SterilizationDtos.ItemView;
import com.orthoflow.sterilization.domain.model.SterilizationItem;
import com.orthoflow.sterilization.domain.model.SterilizationItem.Kind;

import java.util.ArrayList;
import java.util.List;

/** An item as the screens and the scanner see it, including what may be done with it next. */
public final class ItemViews {

    private ItemViews() {
    }

    public static ItemView of(SterilizationItem i) {
        return new ItemView(i.getId(), i.getCode(), i.getName(), i.getKind(), i.getState(), i.getStateChangedAt(), i.getSerialNumber(),
                i.getNotes(), i.isActive(), i.getLastUsedAt(), i.getLastLubricatedAt(), i.lubricationDue(), i.getLastCycleId(), nextActions(i));
    }

    /** What a scan of this item offers. A retired item offers nothing. */
    static List<Action> nextActions(SterilizationItem i) {
        List<Action> out = new ArrayList<>();
        if (!i.isActive()) {
            return out;
        }
        switch (i.getState()) {
            case READY -> out.add(Action.USE);
            case USED -> out.add(Action.CLEAN);
            case DIRTY -> {
                if (i.getKind() == Kind.HANDPIECE) {
                    out.add(Action.LUBRICATE);
                }
                out.add(Action.ADD_TO_CYCLE);
            }
            case PROCESSED -> {
                // Waiting for its cycle's control; nothing to do with the item itself.
            }
        }
        return out;
    }
}
