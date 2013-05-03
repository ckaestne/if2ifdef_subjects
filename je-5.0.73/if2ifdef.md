Configuration parameters for BerkeleyDB are specified in com.sleepycat.je.config.EnvironmentParams many are marked immutable and are read through the infrastructure in various locations in the source code. I've identified the read site of 5 configuration options, some pretty small.

ENV_RECOVERY
ENV_RECOVERY_FORCE_CHECKPOINT
ENV_SHARED_CACHE
ENV_CHECK_LEAKS
ENV_RDONLY (incomplete, may be reconfigured actually)

search for If2Ifdef.makeSymbolic to find all the initialization locations
