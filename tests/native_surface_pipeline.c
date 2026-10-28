/* Exercise the actual JNI implementation with real socket ownership transfers.
 * The fake Android post waits for the next request: moving it after post would
 * deadlock/fail. The producer overwrites shared pixels while the private copy
 * is still being posted, detecting early grants and copy-after-grant mistakes. */
#define _POSIX_C_SOURCE 200809L
#include <assert.h>
#include <pthread.h>
#include <stdio.h>
#include <unistd.h>
#include <sys/time.h>
#include "../native/presentation/android-surface.c"

static uint32_t shared_pixels[LSB_MAX_PIXELS],window_pixels[LSB_MAX_PIXELS];
static ANativeWindow window;
static int array_length=15,geometry_calls,releases,locks,posts,fail_geometry,fail_lock,fail_post;
static int overlap_mode,expected_before_post,requests,unchanged_first,resize_second,bad_header,disconnect_first;
static pthread_mutex_t mutex=PTHREAD_MUTEX_INITIALIZER;
static pthread_cond_t changed=PTHREAD_COND_INITIALIZER;
static jsize array_size(JNIEnv *e,jarray a){(void)e;(void)a;return array_length;}
static void get_longs(JNIEnv *e,jlongArray a,jsize start,jsize len,jlong *out){(void)e;assert(start+len<=array_length);memcpy(out,(jlong *)a+start,(size_t)len*sizeof(jlong));}
static void set_longs(JNIEnv *e,jlongArray a,jsize start,jsize len,const jlong *in){(void)e;assert(start+len<=array_length);memcpy((jlong *)a+start,in,(size_t)len*sizeof(jlong));}
static void *buffer_address(JNIEnv *e,jobject b){(void)e;return b;}
static jlong buffer_capacity(JNIEnv *e,jobject b){(void)e;(void)b;return sizeof(shared_pixels);}
static const struct JNINativeInterface_ functions={.GetArrayLength=array_size,.GetLongArrayRegion=get_longs,.SetLongArrayRegion=set_longs,.GetDirectBufferAddress=buffer_address,.GetDirectBufferCapacity=buffer_capacity};
static JNIEnv env=&functions;
ANativeWindow *ANativeWindow_fromSurface(JNIEnv *e,jobject surface){(void)e;return surface?&window:NULL;}
int ANativeWindow_setBuffersGeometry(ANativeWindow *w,int32_t width,int32_t height,int32_t format){(void)w;assert(width==2||width==3);assert(height==2);assert(format==1);geometry_calls++;return fail_geometry?-1:0;}
int ANativeWindow_lock(ANativeWindow *w,ANativeWindow_Buffer *b,void *r){(void)w;(void)r;locks++;if(fail_lock)return -1;b->width=resize_second&&posts?3:2;b->height=2;b->stride=b->width;b->format=1;b->bits=window_pixels;return 0;}
int ANativeWindow_unlockAndPost(ANativeWindow *w){
    (void)w;posts++;
    if(overlap_mode){
        struct timespec deadline;clock_gettime(CLOCK_REALTIME,&deadline);deadline.tv_sec+=2;
        pthread_mutex_lock(&mutex);
        while(requests<expected_before_post)assert(pthread_cond_timedwait(&changed,&mutex,&deadline)==0);
        pthread_mutex_unlock(&mutex);
    }
    return fail_post?-1:0;
}
void ANativeWindow_release(ANativeWindow *w){assert(w==&window);releases++;}
struct producer {int fd,responses,total_requests;};
static void *produce(void *arg){
    struct producer *p=arg;
    for(int n=1;n<=p->total_requests;n++){
        unsigned char request;assert(recv(p->fd,&request,1,0)==1&&request==1);
        if(n>1&&!(unchanged_first&&n==2)){
            uint32_t wanted=0xff000000u|((uint32_t)(n-1)<<16);
            for(int i=0;i<(resize_second&&n>2?6:4);i++)assert(window_pixels[i]==wanted);
        }
        /* A new request immediately permits overwrite, including during post. */
        for(int i=0;i<6;i++)shared_pixels[i]=(uint32_t)n;
        pthread_mutex_lock(&mutex);requests=n;pthread_cond_broadcast(&changed);pthread_mutex_unlock(&mutex);
        if(disconnect_first){shutdown(p->fd,SHUT_RDWR);return NULL;}
        if(n>p->responses)break;
        int width=resize_second&&n==2?3:2;
        uint32_t h[8]={bad_header?0:LSB_MAGIC,(uint32_t)width,2,(uint32_t)width*4,500,(uint32_t)n,(uint32_t)width*8,1};
        if(unchanged_first&&n==1){h[6]=0;h[7]=3;}
        for(int i=0;i<8;i++)h[i]=htonl(h[i]);
        /* Fragment the header to exercise receive(), not an atomic-message assumption. */
        assert(send(p->fd,h,7,MSG_NOSIGNAL)==7);assert(send(p->fd,(char *)h+7,sizeof(h)-7,MSG_NOSIGNAL)==(ssize_t)sizeof(h)-7);
    }
    return NULL;
}
static jint frame(int fd,jlong *times){return Java_io_github_russianranger_lsb_NativePresentation_frame(&env,NULL,(jobject)&window,fd,(jobject)shared_pixels,(jlongArray)times,overlap_mode?JNI_TRUE:JNI_FALSE);}
static void reset(void){
    geometry_calls=releases=locks=posts=requests=0;fail_geometry=fail_lock=fail_post=0;
    unchanged_first=resize_second=bad_header=disconnect_first=0;array_length=15;
    memset(shared_pixels,0,sizeof(shared_pixels));memset(window_pixels,0,sizeof(window_pixels));
}
static void scenario(int overlap,int idle,int resize,int fault){
    reset();overlap_mode=overlap;unchanged_first=idle;resize_second=resize;
    bad_header=fault==1;disconnect_first=fault==2;fail_geometry=fault==3;fail_lock=fault==4;fail_post=fault==5;
    int count=fault?1:2;
    int grant=overlap&&(!fault||fail_post);
    int pair[2];assert(!socketpair(AF_UNIX,SOCK_STREAM,0,pair));
    struct timeval timeout={.tv_sec=3};for(int i=0;i<2;i++)assert(!setsockopt(pair[i],SOL_SOCKET,SO_RCVTIMEO,&timeout,sizeof(timeout)));
    struct producer producer={pair[1],count,count+grant};pthread_t thread;assert(!pthread_create(&thread,NULL,produce,&producer));
    jlong times[15]={0};
    for(int n=1;n<=count;n++){
        expected_before_post=n+1;
        jint result=frame(pair[0],times);
        if(fault){assert(result<0);break;}
        assert(result==((idle&&n==1)?1:0));
        assert(times[11]==overlap);assert(times[3]>=0&&times[13]>0&&times[14]>=0);
        if(n==2&&overlap)assert(times[14]>0);
    }
    assert(!pthread_join(thread,NULL));
    assert(requests==count+grant);
    if(!fault){assert(posts==count-idle);assert(releases==posts);assert(geometry_calls==(resize?2:1));}
    if(fail_lock||fail_geometry)assert(posts==0&&releases==1);
    if(fail_post)assert(posts==1&&releases==1);
    close(pair[0]);close(pair[1]);
}
int main(void){
    for(int mode=0;mode<=1;mode++){
        scenario(mode,0,0,0);scenario(mode,1,0,0);scenario(mode,0,1,0);
        for(int fault=1;fault<=5;fault++)scenario(mode,0,0,fault);
    }
    reset();array_length=14;jlong short_times[14]={0};assert(frame(-1,short_times)==-2);
    reset();overlap_mode=1;expected_before_post=0;
    int pair[2];assert(!socketpair(AF_UNIX,SOCK_STREAM,0,pair));
    uint32_t h[8]={LSB_MAGIC,2,2,8,500,1,16,1};for(int i=0;i<8;i++)h[i]=htonl(h[i]);
    assert(send(pair[1],h,sizeof(h),0)==sizeof(h));shutdown(pair[1],SHUT_RDWR);
    jlong outstanding[15]={0};outstanding[11]=1;outstanding[12]=(jlong)now_ns();
    assert(frame(pair[0],outstanding)<0);assert(posts==1&&releases==1);assert(outstanding[11]==0);
    close(pair[0]);close(pair[1]);
    /* Socket replacement always uses fresh state; all scenarios start this way. */
    puts("PASS: native request ownership, overlap before blocked post, serial fallback, unchanged frames, resize, fragmented headers, reconnect and error cleanup");
}
