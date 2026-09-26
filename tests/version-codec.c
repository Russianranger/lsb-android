/* Host-side format validation. The vectors come from the original matching
 * polcore encoder, independently of the app's read-only implementation. */
#include <assert.h>
#include <stdio.h>
#include <string.h>
#include "../windows/patch-version.h"
#include "windows/viewer-version-fixture.h"

int main(void){
    const unsigned char *fixtures[]={viewer_fixture_zero,viewer_fixture_official};
    const char *keys[]={"0","001b1394"};char version[18];const char *matched;
    unsigned char changed[289];unsigned checks=0;
    assert(patch_version_zero(viewer_fixture_game_zero,288,version));
    assert(!strcmp(version,"20260921_1"));
    assert(!patch_version_viewer(viewer_fixture_game_zero,288,version,&matched));
    assert(!version[0]&&!matched);checks+=2;
    for(unsigned f=0;f<2;f++){
        memcpy(changed,fixtures[f],288);
        assert(patch_version_viewer(changed,288,version,&matched));
        assert(!strcmp(version,VIEWER_FIXTURE_VERSION)&&!strcmp(matched,keys[f]));
        assert(!memcmp(changed,fixtures[f],288));checks++;
        assert(patch_version_known(changed,288,1000,keys[f],version));
        assert(!patch_version_known(changed,288,1000,keys[1-f],version));
        assert(!patch_version_zero(changed,288,version));checks+=3;
        for(unsigned size=0;size<288;size++){
            assert(!patch_version_viewer(changed,size,version,&matched));
            assert(!version[0]&&!matched);checks++;
        }
        assert(!patch_version_viewer(changed,289,version,&matched));checks++;
        for(unsigned pos=0;pos<288;pos++){
            memcpy(changed,fixtures[f],288);changed[pos]^=0x80;
            assert(!patch_version_viewer(changed,288,version,&matched));
            assert(!version[0]&&!matched);
            assert(changed[pos]==(unsigned char)(fixtures[f][pos]^0x80));checks++;
        }
    }
    const unsigned char *invalid[]={viewer_fixture_bad_prefix,viewer_fixture_bad_padding,
        viewer_fixture_bad_version,viewer_fixture_bad_length,viewer_fixture_unknown_key};
    for(unsigned i=0;i<sizeof(invalid)/sizeof(invalid[0]);i++){
        assert(!patch_version_viewer(invalid[i],288,version,&matched));
        assert(!version[0]&&!matched);checks++;
    }
    assert(!patch_version_known(viewer_fixture_zero,288,0,"0",version));
    assert(!patch_version_known(viewer_fixture_zero,288,0x1000,"0",version));
    assert(!patch_version_known(viewer_fixture_zero,288,1000,"other",version));
    assert(!patch_version_known(viewer_fixture_zero,288,1000,NULL,version));
    assert(!patch_version_known(NULL,288,1000,"0",version));checks+=5;
    printf("PASS: %u version codec checks; two original-encoder viewer vectors, wrong keys/content IDs, truncation, all-byte corruption, canonical padding/grammar, source preservation\n",checks);
    return 0;
}
