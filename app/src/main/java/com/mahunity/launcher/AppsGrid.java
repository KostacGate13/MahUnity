package com.mahunity.launcher;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.BaseAdapter;
import android.widget.GridView;

import java.util.Collections;
import java.util.List;

/** All apps, in as many columns as the width takes at a size a finger finds easily; the edges fade into black. */
final class AppsGrid extends GridView {
    private final Adapter adapter;

    AppsGrid(Context context, Apps apps) {
        super(context);
        adapter = new Adapter(context, apps);
        setAdapter(adapter);
        setSelector(new ColorDrawable(Color.TRANSPARENT));
        setDrawSelectorOnTop(false);
        setVerticalScrollBarEnabled(false);
        setOverScrollMode(OVER_SCROLL_NEVER);
        setVerticalFadingEdgeEnabled(true);
        setClipToPadding(false);
        setStretchMode(STRETCH_COLUMN_WIDTH);
        setCacheColorHint(Color.TRANSPARENT);
    }

    void setApps(List<AppEntry> apps) {
        adapter.items = apps;
        adapter.notifyDataSetChanged();
    }

    AppEntry app(int position) {
        return adapter.getItem(position);
    }

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        int w = MeasureSpec.getSize(widthSpec), h = MeasureSpec.getSize(heightSpec);
        if (w > 0 && h > 0) {
            int pad = Math.round(Math.min(h, w * 0.6f) * 0.035f);
            int cell = Math.round(Math.min(h / 3.3f, (w - 2 * pad) / 4.2f));
            if (getPaddingLeft() != pad) setPadding(pad, pad / 2, pad, pad);
            setNumColumns(Math.max(3, (w - 2 * pad) / Math.max(1, Math.round(cell * 0.95f))));
            setFadingEdgeLength(pad * 2);
            if (cell != adapter.cellHeight) {
                adapter.cellHeight = cell;
                adapter.notifyDataSetChanged();
            }
        }
        super.onMeasure(widthSpec, heightSpec);
    }

    private static final class Adapter extends BaseAdapter {
        private final Context context;
        private final Apps apps;
        List<AppEntry> items = Collections.emptyList();
        int cellHeight;

        Adapter(Context context, Apps apps) {
            this.context = context;
            this.apps = apps;
        }

        @Override public int getCount() {
            return items.size();
        }

        @Override public AppEntry getItem(int position) {
            return items.get(position);
        }

        @Override public long getItemId(int position) {
            return position;
        }

        @Override public View getView(int position, View convert, ViewGroup parent) {
            AppCell cell = convert instanceof AppCell ? (AppCell) convert : new AppCell(context, apps);
            ViewGroup.LayoutParams lp = cell.getLayoutParams();
            if (lp == null || lp.height != cellHeight) {
                cell.setLayoutParams(new AbsListView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, cellHeight));
            }
            cell.bind(items.get(position));
            return cell;
        }
    }
}
