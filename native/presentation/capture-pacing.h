#ifndef LSB_CAPTURE_PACING_H
#define LSB_CAPTURE_PACING_H
#include <stdint.h>
/* Normal wake-up error must not move every later deadline. After a whole
 * missed interval, rebase instead of issuing a burst of catch-up captures. */
static inline uint64_t lsb_capture_deadline(uint64_t next,uint64_t now,uint64_t interval){
    return !next||(now>next&&now-next>=interval)?now:next;
}
static inline uint64_t lsb_capture_next(uint64_t deadline,uint64_t now,uint64_t interval){
    return (now>deadline&&now-deadline>=interval?now:deadline)+interval;
}
#endif
