import math
# 3rd and 2nd harmonic of f(x)=tanh(k x)/k for x=A sin(theta), minus nothing (wet excludes only the fundamental).
def harmonics(A, k, n=4096):
    def c(h):
        s=0.0
        for i in range(n):
            th=2*math.pi*(i+0.5)/n
            s+=math.tanh(k*A*math.sin(th))/k*math.sin(h*th)
        return 2*s/n
    f=c(1)
    return f, c(3), c(5)
A=0.25
print("k    fundamental  3rd dBc   5th dBc   (A=0.25, i.e. -12 dBFS)")
for k in (2.5,3.5,4.5,5.5,6.5):
    f,h3,h5=harmonics(A,k)
    print(f"{k:3.1f}  {f/A:8.3f}   {20*math.log10(abs(h3)/A):7.1f}   {20*math.log10(abs(h5)/A):7.1f}")
