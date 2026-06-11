-- 1. skill_id 2.user_id 3.id
local res = redis.call("SISMEMBER",KEYS[1],ARGV[2])
local stock = tonumber(redis.call("get", ARGV[1]))
if stock <= 0 then
    return 1
end
if res == 1 then
    return 2
end
redis.call("DECRBY", ARGV[1], 1)
redis.call("SADD", KEYS[1], ARGV[2])
redis.call("xadd","stream.orders","*","userId",ARGV[2],"voucherId",ARGV[4],"id",ARGV[3])
return 0