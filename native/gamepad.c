/* App-owned SDL virtual joystick for Wine's SDL HID bus. No kernel input access.
 * Loaded in the ARM64 host; waits for Wine/Box64 to load and initialize SDL. */
typedef __UINT32_TYPE__ u32;
typedef __UINT64_TYPE__ u64;
typedef __INT16_TYPE__ i16;
typedef __SIZE_TYPE__ usize;
extern void *dlopen(const char *, int);
extern void *dlsym(void *, const char *);
extern char *getenv(const char *);
extern int pthread_create(unsigned long *, const void *, void *(*)(void *), void *);
extern int pthread_detach(unsigned long);
extern int usleep(unsigned);
extern int open(const char *, int, ...);
extern int close(int);
extern long lseek(int, long, int);
extern long write(int, const void *, usize);
extern long read(int, void *, usize);
extern void *mmap(void *, usize, int, int, int, long);
struct timespec { long sec, ns; };
extern int clock_gettime(int, struct timespec *);

static void report(const char *message, usize length) {
    int fd=open("/session/gamepad-bridge.json", 1|64|512, 0600);
    if(fd>=0){write(fd,message,length);close(fd);}
}
#define REPORT(s) report(s,sizeof(s)-1)
static int hid_host(void) {
    /* Only winedevice.exe hosts Wine's SDL HID bus. In particular, do not
     * create a polling/dlopen thread in the dependency checker or xiloader. */
    char args[4096];int fd=open("/proc/self/cmdline",0);
    if(fd<0)return 0;
    long n=read(fd,args,sizeof(args));close(fd);
    for(long start=0;start<n;) {
        long end=start;while(end<n&&args[end])end++;
        if(end==n)break; /* Ignore an incomplete argument. */
        long base=start;for(long i=start;i<end;i++)if(args[i]=='/'||args[i]=='\\')base=i+1;
        const char expected[]="winedevice.exe";
        if(end-base==(long)sizeof(expected)-1) {
            int same=1;for(long i=0;i<end-base;i++) {
                char c=args[base+i];if(c>='A'&&c<='Z')c+=32;
                if(c!=expected[i]){same=0;break;}
            }
            if(same)return 1;
        }
        start=end+1;
    }
    return 0;
}
static void *bridge(void *unused) {
    (void)unused;
    const char *path=getenv("LSB_GAMEPAD_STATE");
    if(!path)return 0;
    int fd=open(path,0);if(fd<0)return 0;
    if(lseek(fd,0,2)!=64){close(fd);return 0;}
    volatile unsigned char *data=mmap(0,64,1,1,fd,0);close(fd);
    if(data==(void*)-1)return 0;
    void *sdl=0;
    u32 (*initialized)(u32)=0;
    /* Only the Wine HID host initializes SDL joysticks. Other Wine processes
     * leave without creating a controller or holding a background thread. */
    for(int i=0;i<600;i++) {
        if(!sdl)sdl=dlopen("libSDL2-2.0.so.0",2|4);
        if(sdl&&!initialized)initialized=(void*)dlsym(sdl,"SDL_WasInit");
        if(initialized&&(initialized(0x200)&0x200))break;
        usleep(100000);
    }
    if(!initialized||!(initialized(0x200)&0x200))return 0;
    int (*attach)(int,int,int,int)=(void*)dlsym(sdl,"SDL_JoystickAttachVirtual");
    void *(*joyopen)(int)=(void*)dlsym(sdl,"SDL_JoystickOpen");
    int (*axis)(void*,int,i16)=(void*)dlsym(sdl,"SDL_JoystickSetVirtualAxis");
    int (*button)(void*,int,unsigned char)=(void*)dlsym(sdl,"SDL_JoystickSetVirtualButton");
    int (*hat)(void*,int,unsigned char)=(void*)dlsym(sdl,"SDL_JoystickSetVirtualHat");
    void (*update)(void)=(void*)dlsym(sdl,"SDL_JoystickUpdate");
    int (*hint)(const char*,const char*)=(void*)dlsym(sdl,"SDL_SetHint");
    if(!attach||!joyopen||!axis||!button||!hat||!update){REPORT("{\"phase\":\"unsupported_sdl\"}\n");return 0;}
    if(hint)hint("SDL_JOYSTICK_ALLOW_BACKGROUND_EVENTS","1");
    /* Wine assigns SDL joystick slots X,Y,Z,Rx,Ry,Rz in order. Keep the
     * unused Rx/Ry slots neutral so the fourth Android axis reaches Rz. */
    int index=attach(0,6,16,1);
    void *joy=index<0?0:joyopen(index);
    if(!joy){REPORT("{\"phase\":\"attach_failed\"}\n");return 0;}
    REPORT("{\"phase\":\"attached\",\"axes\":6,\"stick_axes\":\"X,Y,Z,Rz\",\"buttons\":16,\"hats\":1}\n");
    u32 previous=0xffffffff,was_stale=1;
    for(;;) {
        u32 first=__atomic_load_n((volatile u32*)(data+4),__ATOMIC_ACQUIRE);
        if(!(first&1)&&*(volatile u32*)data==0x4c534247) {
            u32 buttons=*(volatile u32*)(data+8),pov=*(volatile u32*)(data+12);
            i16 axes[4];for(int i=0;i<4;i++)axes[i]=*(volatile i16*)(data+16+i*2);
            u64 stamp=*(volatile u64*)(data+32);
            __atomic_thread_fence(__ATOMIC_ACQUIRE);
            u32 last=__atomic_load_n((volatile u32*)(data+4),__ATOMIC_ACQUIRE);
            struct timespec now;clock_gettime(1,&now);
            u64 millis=(u64)now.sec*1000+now.ns/1000000;
            u32 stale=stamp>millis||millis-stamp>1500;
            if(first==last&&(first!=previous||stale!=was_stale)) {
                for(int i=0;i<6;i++)axis(joy,i,stale||i==3||i==4?0:axes[i==5?3:i]);
                for(int i=0;i<16;i++)button(joy,i,stale?0:(buttons>>i)&1);
                hat(joy,0,stale?0:pov&15);update();previous=first;was_stale=stale;
            }
        }
        usleep(10000);
    }
    return 0;
}
__attribute__((constructor)) static void start(void) {
    if(!getenv("LSB_GAMEPAD_STATE")||!hid_host())return;
    unsigned long thread;if(!pthread_create(&thread,0,bridge,0))pthread_detach(thread);
}
