from adafruit_circuitplayground.express import cpx 
import time 

# Change the color here.
color = (0,0,255) 

delay = 50 

pixels = cpx.pixels 
pixels.brightness = 0.05 
pixels.fill((0, 0, 0)) 
pixels.show() 


while True: 
    for i in range(0, len(pixels)): 
        pixels[i] = color 
        time.sleep(delay * 1/1000) 

    for i in range(0, len(pixels)): 
        pixels[i] = (0,0,0) 
        time.sleep(delay * 1/1000) 