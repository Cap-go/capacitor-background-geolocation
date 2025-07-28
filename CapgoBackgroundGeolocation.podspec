
  Pod::Spec.new do |s|
    s.name = 'CapgoBackgroundGeolocation'
    s.version = '0.0.1'
    s.summary = 'Capacitor plugin which lets you receive geolocation updates even while the app is backgrounded.'
    s.license = 'MIT'
    s.homepage = 'https://github.com/Cap-go/background-geolocation'
    s.author = 'Cap-go'
    s.source = { :git => 'https://github.com/Cap-go/background-geolocation', :tag => s.version.to_s }
    s.source_files = 'ios/Plugin/**/*.{swift,h,m,c,cc,mm,cpp}'
    s.ios.deployment_target  = '14.0'
    s.dependency 'Capacitor'
  end
