package io.github.russianranger.lsb;

import android.content.Context;
import android.graphics.*;
import android.graphics.drawable.Drawable;
import android.view.*;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.*;
import java.util.*;

/** Compact section selectors; the selected panel spans the row below them. */
public final class FantasyTiles extends LinearLayout {
    static final int GOLD=0xffd9b875, INK=0xff101c2d, TEXT=0xfff4ead5;
    private final ArrayList<Entry> entries=new ArrayList<>();
    private String selected="";
    private int columns;
    private final java.util.function.Consumer<String> selectionChanged;
    private static final class Entry {
        final String title; final View body;
        Entry(String title,View body){this.title=title;this.body=body;}
    }
    public FantasyTiles(Context context,String selected,java.util.function.Consumer<String> changed){
        super(context);setOrientation(VERTICAL);this.selected=selected;selectionChanged=changed;
    }
    public void addSection(String title,View body){entries.add(new Entry(title,body));}
    private int dp(int n){return Math.round(n*getResources().getDisplayMetrics().density);}
    static int columnsFor(float widthDp,float fontScale){
        float usable=widthDp/Math.max(1f,fontScale);
        return usable>=680?3:usable>=290?2:1;
    }
    @Override protected void onMeasure(int widthSpec,int heightSpec){
        int next=columnsFor(MeasureSpec.getSize(widthSpec)/getResources().getDisplayMetrics().density,getResources().getConfiguration().fontScale);
        if(next!=columns){columns=next;rebuild();}
        super.onMeasure(widthSpec,heightSpec);
    }
    void select(String title){
        selected=title.equals(selected)?"":title;selectionChanged.accept(selected);rebuild();
    }
    private void rebuild(){
        for(Entry e:entries)if(e.body.getParent()!=null)((android.view.ViewGroup)e.body.getParent()).removeView(e.body);
        removeAllViews();
        for(int start=0;start<entries.size();start+=Math.max(1,columns)){
            LinearLayout row=new LinearLayout(getContext());row.setBaselineAligned(false);
            addView(row,new LayoutParams(-1,-2));Entry expanded=null;
            for(int col=0;col<Math.max(1,columns);col++){
                LayoutParams cell=new LayoutParams(0,-1,1);cell.setMargins(dp(4),dp(5),dp(4),dp(5));
                if(start+col>=entries.size()){row.addView(new View(getContext()),cell);continue;}
                Entry e=entries.get(start+col);boolean open=e.title.equals(selected);
                TextView tile=new TextView(getContext());tile.setText("◇  "+e.title+"\n"+(open?"Close  −":"Open  +"));
                tile.setTextColor(open?TEXT:GOLD);tile.setTextSize(17);tile.setTypeface(Typeface.create("serif",Typeface.BOLD));
                tile.setGravity(Gravity.CENTER);tile.setPadding(dp(12),dp(18),dp(12),dp(18));tile.setMinHeight(dp(100));
                tile.setBackground(new Panel(getResources().getDisplayMetrics().density,open));
                tile.setClickable(true);tile.setFocusable(true);tile.setSelected(open);
                tile.setContentDescription(e.title+", "+(open?"expanded":"collapsed"));
                tile.setAccessibilityDelegate(new View.AccessibilityDelegate(){
                    @Override public void onInitializeAccessibilityNodeInfo(View host,AccessibilityNodeInfo info){
                        super.onInitializeAccessibilityNodeInfo(host,info);info.setClassName(Button.class.getName());
                    }
                });
                tile.setOnClickListener(v->select(e.title));row.addView(tile,cell);if(open)expanded=e;
            }
            if(expanded!=null){LayoutParams detail=new LayoutParams(-1,-2);detail.setMargins(dp(4),0,dp(4),dp(8));addView(expanded.body,detail);}
        }
    }
    /** Painted ornament stays crisp at every density and needs no bitmap allocation. */
    static final class Panel extends Drawable {
        private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
        private final float scale;private final boolean active;
        Panel(float scale,boolean active){this.scale=scale;this.active=active;}
        @Override public void draw(Canvas canvas){
            Rect b=getBounds();float s=scale;RectF box=new RectF(b.left+s,b.top+s,b.right-s,b.bottom-s);
            paint.setStyle(Paint.Style.FILL);paint.setShader(new LinearGradient(0,b.top,0,b.bottom,
                active?0xff294654:0xff233448,INK,Shader.TileMode.CLAMP));canvas.drawRoundRect(box,10*s,10*s,paint);paint.setShader(null);
            paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(s);paint.setColor(active?GOLD:0xff71634b);canvas.drawRoundRect(box,10*s,10*s,paint);
            paint.setColor(GOLD);float d=9*s,n=10*s;
            for(int x:new int[]{-1,1})for(int y:new int[]{-1,1}){
                float px=x<0?box.left+d:box.right-d,py=y<0?box.top+d:box.bottom-d;
                canvas.drawLine(px,py,px-x*n,py,paint);canvas.drawLine(px,py,px,py-y*n,paint);
            }
        }
        @Override public void setAlpha(int alpha){paint.setAlpha(alpha);}
        @Override public void setColorFilter(ColorFilter filter){paint.setColorFilter(filter);}
        @Override public int getOpacity(){return PixelFormat.TRANSLUCENT;}
    }
}
