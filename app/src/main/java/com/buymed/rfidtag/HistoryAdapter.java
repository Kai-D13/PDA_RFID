package com.buymed.rfidtag;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.TextView;

import java.util.List;

final class HistoryAdapter extends BaseAdapter {
    private final LayoutInflater inflater;
    private final List<LookupResult> items;

    HistoryAdapter(Context ctx, List<LookupResult> items) {
        this.inflater = LayoutInflater.from(ctx);
        this.items = items;
    }

    @Override public int getCount() { return items.size(); }
    @Override public Object getItem(int position) { return items.get(position); }
    @Override public long getItemId(int position) { return position; }

    @Override
    public View getView(int position, View view, ViewGroup parent) {
        if (view == null) view = inflater.inflate(R.layout.item_history, parent, false);
        LookupResult r = items.get(position);
        TextView tag = (TextView) view.findViewById(R.id.h_tag);
        TextView title = (TextView) view.findViewById(R.id.h_title);
        TextView sub = (TextView) view.findViewById(R.id.h_sub);
        TextView stock = (TextView) view.findViewById(R.id.h_stock);
        tag.setText(r.shortTag());
        ((GradientDrawable) tag.getBackground().mutate()).setColor(r.color());
        String name = r.displayName();
        title.setText(name != null ? name : r.error != null ? r.error : r.headline());
        StringBuilder s = new StringBuilder(DateFmt.time(r.time));
        if (r.productId >= 0) s.append("  ·  ID ").append(r.productId);
        if (r.code != null) s.append("  ·  ").append(r.code);
        else if (r.input != null && r.productId < 0) s.append("  ·  ").append(r.input);
        sub.setText(s);
        // Setup errors ("chưa chọn kho"…) are shown on the main panel, not repeated on every row.
        String st = r.hasProduct() && (r.stock != null || r.stockLoading) ? r.stockSummary() : null;
        stock.setText(st != null ? "Tồn: " + st : "");
        stock.setVisibility(st != null ? View.VISIBLE : View.GONE);
        return view;
    }
}
