/* Read-only compatibility check for known Interface patch.ver formats.
 * Derived from the supplied polcore decoder (RVAs 0x7bc0, 0x8000, 0x8130),
 * independently checked against its original instructions. No client bytes,
 * arbitrary installation keys, or writable codec are bundled.
 */
#include <stdint.h>
#include <string.h>

static uint32_t patch_u32(const unsigned char *p){
    return (uint32_t)p[0]|(uint32_t)p[1]<<8|(uint32_t)p[2]<<16|(uint32_t)p[3]<<24;
}
static uint64_t patch_u64(const unsigned char *p){return patch_u32(p)|(uint64_t)patch_u32(p+4)<<32;}
static void patch_bytes(unsigned char *p,uint64_t value){for(unsigned n=0;n<8;n++){p[n]=(unsigned char)value;value>>=8;}}

/* Accept only a complete, checksummed, canonically padded version record.
 * This deliberately rejects formats that this repair cannot validate. */
static int patch_version_known(const unsigned char *blob,size_t size,unsigned content_id,
                               const char *key,char version[18]){
    unsigned char schedule[8],plain[288];uint64_t word;uint32_t sum=0;
    version[0]=0;if(size!=sizeof(plain)||!blob||!key||
        (content_id!=1&&content_id!=1000)||
        (strcmp(key,"0")&&strcmp(key,"001b1394")))return 0;
    /* polcore adds the decimal content id to the cyclic eight-byte registry
     * accumulator, rotates each half, then swaps them. The two candidate keys
     * are the existing common format and the official viewer installer's
     * documented value. A candidate is never accepted without full validation. */
    uint64_t seed=0;unsigned char accumulator[8]={0};
    for(unsigned n=0;key[n];n++)accumulator[n&7]=(unsigned char)(accumulator[n&7]+(unsigned char)key[n]);
    seed=patch_u64(accumulator)+content_id;
    uint32_t low=(uint32_t)seed,high=(uint32_t)(seed>>32);
    low=(low<<8)|(low>>24);high=(high>>16)|(high<<16);
    patch_bytes(schedule,(uint64_t)low<<32|high);
    schedule[0]=(unsigned char)(schedule[0]+0x45);
    for(unsigned n=1;n<8;n++)schedule[n]=(unsigned char)(((schedule[n]+schedule[n-1]-0x2c)&255)^(schedule[n-1]<<2)^0x45);
    uint64_t first=patch_u64(schedule);word=first;
    for(unsigned block=0;block<36;block++){
        if(block==32)word=first;
        unsigned char bytes[8];patch_bytes(bytes,word);unsigned add=0x40;
        for(unsigned n=0;n<8;n++)add+=bytes[n];
        unsigned char previous=(unsigned char)(block^0x45);
        for(unsigned n=0;n<8;n++){
            unsigned char current=blob[block*8+n];
            bytes[n]=(unsigned char)((current^previous)-add);previous=current;
        }
        uint64_t counter=block*8,mix=counter;
        for(unsigned n=0;n<3;n++)mix=(mix<<10)|counter;
        uint64_t value=(patch_u64(bytes)-word)^(mix+UINT64_C(0xa1652347))^word;
        value=(value<<32)|(value>>32);patch_bytes(plain+block*8,value);
        if(block<35)sum+=plain[block*8]+plain[block*8+4];
        word*=5;
    }
    if(patch_u32(plain+280)!=280||patch_u32(plain+284)!=sum)return 0;
    for(unsigned n=0;n<24;n++)if(plain[n])return 0;
    unsigned length=0;while(length<18&&plain[24+length])length++;
    if(length<10||length>17||plain[32]!='_')return 0;
    for(unsigned n=0;n<8;n++)if(plain[24+n]<'0'||plain[24+n]>'9')return 0;
    for(unsigned n=9;n<length;n++){
        unsigned char c=plain[24+n];
        if(!((c>='0'&&c<='9')||(c>='A'&&c<='Z')||(c>='a'&&c<='z')))return 0;
    }
    for(unsigned n=24+length;n<280;n++)if(plain[n])return 0;
    memcpy(version,plain+24,length);version[length]=0;return 1;
}

/* Preserve the established game decoder's behavior and narrow key scope. */
static int patch_version_zero(const unsigned char *blob,size_t size,char version[18]){
    return patch_version_known(blob,size,1,"0",version);
}

/* Never choose a registry value when no candidate or multiple candidates match. */
static int patch_version_viewer(const unsigned char *blob,size_t size,char version[18],
                                const char **matched_key){
    static const char *const candidates[]={"0","001b1394"};
    char checked[18]={0},selected[18]={0};unsigned matches=0;const char *key=NULL;
    version[0]=0;*matched_key=NULL;
    for(unsigned n=0;n<sizeof(candidates)/sizeof(candidates[0]);n++)
        if(patch_version_known(blob,size,1000,candidates[n],checked)){
            matches++;key=candidates[n];memcpy(selected,checked,sizeof(selected));
        }
    if(matches!=1)return 0;
    memcpy(version,selected,sizeof(selected));*matched_key=key;return 1;
}
