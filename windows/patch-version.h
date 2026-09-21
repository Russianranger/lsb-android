/* Read-only compatibility check for the common Interface="0" patch.ver format.
 * Derived from the supplied polcore decoder (RVAs 0x7bc0, 0x8000, 0x8130),
 * independently checked against its original instructions. No client bytes,
 * encryption keys for other installations, or writable codec are bundled.
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
static int patch_version_zero(const unsigned char *blob,size_t size,char version[18]){
    unsigned char schedule[8],plain[288];uint64_t word;uint32_t sum=0;
    version[0]=0;if(size!=sizeof(plain))return 0;
    /* content id 1 + ASCII '0' (0x30); rotate/swap the seed as polcore does. */
    patch_bytes(schedule,(uint64_t)0x3100<<32);
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
