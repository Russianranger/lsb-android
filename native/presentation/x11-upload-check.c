#include <xcb/xcb.h>
#include <stdint.h>
#include <stdlib.h>
#include <stdio.h>
#include <string.h>

/* Alternating contents, ring wrap, resize, two connections and checked errors.
 * Readback fences only groups of uploads, so a broken reuse fence corrupts
 * earlier drawables instead of being hidden by a round trip after every frame. */
int main(void){
    for(int connection=0;connection<2;connection++){
        xcb_connection_t *c=xcb_connect(NULL,NULL);if(xcb_connection_has_error(c))return 10;
        xcb_screen_t *screen=xcb_setup_roots_iterator(xcb_get_setup(c)).data;
        xcb_gcontext_t gc=xcb_generate_id(c);xcb_create_gc(c,gc,screen->root,0,NULL);
        for(int round=0;round<3;round++){
            unsigned width=round==1?480:320,height=round==2?1:240,count=width*height;
            uint32_t *data=malloc(count*4);if(!data)return 11;
            xcb_pixmap_t images[9];
            for(unsigned j=0;j<9;j++){
                images[j]=xcb_generate_id(c);xcb_create_pixmap(c,24,images[j],screen->root,width,height);
                for(unsigned k=0;k<count;k++)data[k]=(0x123456+j*0x120700+k)&0xffffff;
                xcb_put_image(c,XCB_IMAGE_FORMAT_Z_PIXMAP,images[j],gc,width,height,0,0,0,24,count*4,(uint8_t*)data);
                memset(data,0,count*4); /* The caller may immediately overwrite its input. */
            }
            for(unsigned j=0;j<9;j++){
                xcb_get_image_reply_t *r=xcb_get_image_reply(c,xcb_get_image(c,XCB_IMAGE_FORMAT_Z_PIXMAP,images[j],0,0,width,height,~0u),NULL);
                if(!r||xcb_get_image_data_length(r)!=(int)count*4)return 12;
                uint32_t *pixels=(uint32_t*)xcb_get_image_data(r);
                for(unsigned k=0;k<count;k++)if((pixels[k]&0xffffff)!=((0x123456+j*0x120700+k)&0xffffff))return 13;
                free(r);xcb_free_pixmap(c,images[j]);
            }
            if(height>1){
                xcb_generic_error_t *e=xcb_request_check(c,xcb_put_image_checked(c,XCB_IMAGE_FORMAT_Z_PIXMAP,0,gc,width,height,0,0,0,24,count*4,(uint8_t*)data));
                if(!e)return 14;
                free(e);
            }
            free(data);
        }
        xcb_free_gc(c,gc);xcb_disconnect(c);
    }
    puts("PASS: XCB upload pixels, ownership, ring reuse, resize, row fallback, checked errors and reconnect");return 0;
}
