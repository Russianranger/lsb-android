/* Native-host XCB upload transport. Rendering/readback remain owned by Mesa.
 * Only packed 24/32-bit ZPixmap transfers are eligible. A reply queued AFTER
 * each upload fences slot reuse; no application events or replies are consumed.
 */
#define _GNU_SOURCE
#include <xcb/xcb.h>
#include <xcb/shm.h>
#include <dlfcn.h>
#include <pthread.h>
#include <sys/shm.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <fcntl.h>
#include <unistd.h>
#include <stdint.h>
#include <stdlib.h>
#include <stdio.h>
#include <string.h>
#include <time.h>

typedef xcb_void_cookie_t (*put_fn)(xcb_connection_t*,uint8_t,xcb_drawable_t,xcb_gcontext_t,uint16_t,uint16_t,int16_t,int16_t,uint8_t,uint8_t,uint32_t,const uint8_t*);
struct slot { void *data; size_t size; xcb_shm_seg_t segment; xcb_get_input_focus_cookie_t fence; };
struct channel { xcb_connection_t *connection; int enabled; unsigned next; struct slot slots[3]; };
static struct channel channels[4];
static pthread_mutex_t lock=PTHREAD_MUTEX_INITIALIZER;
static pthread_once_t symbols_once=PTHREAD_ONCE_INIT;
static put_fn original_put,original_checked;
static void (*original_disconnect)(xcb_connection_t*);
static uint64_t dummy[12],*stats=dummy;
static int stats_started;
static uint64_t ns(void){struct timespec t;clock_gettime(CLOCK_MONOTONIC,&t);return (uint64_t)t.tv_sec*1000000000+t.tv_nsec;}
static void symbols(void){original_put=(put_fn)dlsym(RTLD_NEXT,"xcb_put_image");original_checked=(put_fn)dlsym(RTLD_NEXT,"xcb_put_image_checked");original_disconnect=dlsym(RTLD_NEXT,"xcb_disconnect");}
static void start_stats(void){
    if(stats_started)return;
    stats_started=1;
    const char *dir=getenv("LSB_X11_UPLOAD_STATS");if(!dir)return;
    char path[512];if(snprintf(path,sizeof(path),"%s/wsi-upload-%ld.bin",dir,(long)getpid())>=(int)sizeof(path))return;
    int fd=open(path,O_RDWR|O_CREAT|O_EXCL|O_CLOEXEC|O_NOFOLLOW,0600);if(fd<0)return;
    if(ftruncate(fd,sizeof(dummy))==0){void *p=mmap(NULL,sizeof(dummy),PROT_READ|PROT_WRITE,MAP_SHARED,fd,0);if(p!=MAP_FAILED)stats=p;}
    close(fd);stats[0]=0x4c534257534931; /* LSBWSI1, twelve LE uint64 counters; no strings. */
}
static int finish(xcb_connection_t *c,struct slot *s){
    if(!s->fence.sequence)return 1;
    uint64_t begin=ns();xcb_get_input_focus_reply_t *r=xcb_get_input_focus_reply(c,s->fence,NULL);
    uint64_t elapsed=ns()-begin;stats[6]+=elapsed;if(elapsed>stats[9])stats[9]=elapsed;stats[11]++;
    s->fence.sequence=0;int ok=r!=NULL;free(r);return ok;
}
static void release_slot(xcb_connection_t *c,struct slot *s){
    if(!s->data)return;
    finish(c,s);xcb_shm_detach(c,s->segment);shmdt(s->data);memset(s,0,sizeof(*s));
}
static struct channel *channel(xcb_connection_t *c){
    struct channel *available=NULL;
    for(unsigned i=0;i<4;i++){if(channels[i].connection==c)return &channels[i];if(!channels[i].connection&&!available)available=&channels[i];}
    if(!available)return NULL;
    available->connection=c;stats[8]++;
    const xcb_query_extension_reply_t *e=xcb_get_extension_data(c,&xcb_shm_id);
    /* Validate the server format; depth 24 alone does not imply 32-bit pixels. */
    const xcb_setup_t *setup=xcb_get_setup(c);int packed=0;
    if(setup)for(xcb_format_iterator_t i=xcb_setup_pixmap_formats_iterator(setup);i.rem;xcb_format_next(&i))
        if(i.data->depth==24&&i.data->bits_per_pixel==32&&i.data->scanline_pad==32)packed=1;
    available->enabled=e&&e->present&&packed&&!getenv("LSB_X11_UPLOAD_DISABLE_SHM");
    return available;
}
static int reserve(xcb_connection_t *c,struct slot *s,size_t size){
    if(!finish(c,s))return 0;
    if(s->size>=size)return 1;
    release_slot(c,s);
    int id=shmget(IPC_PRIVATE,size,IPC_CREAT|0600);if(id<0)return 0;
    void *data=shmat(id,NULL,0);if(data==(void*)-1){shmctl(id,IPC_RMID,NULL);return 0;}
    xcb_shm_seg_t segment=xcb_generate_id(c);
    xcb_generic_error_t *error=xcb_request_check(c,xcb_shm_attach_checked(c,segment,id,1));
    shmctl(id,IPC_RMID,NULL);
    if(error||xcb_connection_has_error(c)){free(error);shmdt(data);return 0;}
    s->data=data;s->size=size;s->segment=segment;return 1;
}
static xcb_void_cookie_t upload(int checked,xcb_connection_t *c,uint8_t format,xcb_drawable_t drawable,xcb_gcontext_t gc,uint16_t width,uint16_t height,int16_t x,int16_t y,uint8_t left,uint8_t depth,uint32_t length,const uint8_t *data){
    pthread_once(&symbols_once,symbols);
    put_fn real=checked?original_checked:original_put;
    pthread_mutex_lock(&lock);start_stats();stats[1]++;
    /* Leave row damage uploads, uncommon formats and oversized images alone. */
    if(getenv("LSB_X11_UPLOAD")&&format==XCB_IMAGE_FORMAT_Z_PIXMAP&&depth==24&&!left&&width&&height>=16&&length==(uint64_t)width*height*4&&length<=16*1024*1024){
        struct channel *ch=channel(c);
        if(ch&&ch->enabled){
            struct slot *s=&ch->slots[ch->next%3];
            if(reserve(c,s,length)){
                uint64_t begin=ns();memcpy(s->data,data,length);uint64_t elapsed=ns()-begin;
                stats[5]+=elapsed;if(elapsed>stats[10])stats[10]=elapsed;
                xcb_void_cookie_t cookie=checked?
                    xcb_shm_put_image_checked(c,drawable,gc,width,height,0,0,width,height,x,y,depth,format,0,s->segment,0):
                    xcb_shm_put_image(c,drawable,gc,width,height,0,0,width,height,x,y,depth,format,0,s->segment,0);
                s->fence=xcb_get_input_focus(c);ch->next++;stats[2]++;stats[4]+=length;
                pthread_mutex_unlock(&lock);return cookie;
            }
            ch->enabled=0;stats[7]++;
        }
    }
    stats[3]++;pthread_mutex_unlock(&lock);
    return real(c,format,drawable,gc,width,height,x,y,left,depth,length,data);
}
xcb_void_cookie_t xcb_put_image(xcb_connection_t *c,uint8_t f,xcb_drawable_t d,xcb_gcontext_t g,uint16_t w,uint16_t h,int16_t x,int16_t y,uint8_t l,uint8_t z,uint32_t n,const uint8_t *p){return upload(0,c,f,d,g,w,h,x,y,l,z,n,p);}
xcb_void_cookie_t xcb_put_image_checked(xcb_connection_t *c,uint8_t f,xcb_drawable_t d,xcb_gcontext_t g,uint16_t w,uint16_t h,int16_t x,int16_t y,uint8_t l,uint8_t z,uint32_t n,const uint8_t *p){return upload(1,c,f,d,g,w,h,x,y,l,z,n,p);}
void xcb_disconnect(xcb_connection_t *c){
    pthread_once(&symbols_once,symbols);pthread_mutex_lock(&lock);
    for(unsigned i=0;i<4;i++)if(channels[i].connection==c){for(unsigned j=0;j<3;j++)release_slot(c,&channels[i].slots[j]);memset(&channels[i],0,sizeof(channels[i]));}
    pthread_mutex_unlock(&lock);original_disconnect(c);
}
static void before_fork(void){pthread_mutex_lock(&lock);}
static void after_fork(void){pthread_mutex_unlock(&lock);}
static void child_fork(void){
    for(unsigned i=0;i<4;i++)for(unsigned j=0;j<3;j++)if(channels[i].slots[j].data)shmdt(channels[i].slots[j].data);
    memset(channels,0,sizeof(channels));if(stats!=dummy)munmap(stats,sizeof(dummy));memset(dummy,0,sizeof(dummy));stats=dummy;stats_started=0;pthread_mutex_unlock(&lock);
}
__attribute__((constructor)) static void initialize(void){pthread_atfork(before_fork,after_fork,child_fork);}
