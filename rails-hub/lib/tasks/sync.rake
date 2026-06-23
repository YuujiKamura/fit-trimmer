namespace :sync do
  desc "Scan Google Drive folder and register videos in DB"
  task scan: :environment do
    manifest_path = "H:/マイドライブ/20260621/sync_manifest.json"
    unless File.exist?(manifest_path)
      puts "Manifest not found: #{manifest_path}"
      next
    end

    data = JSON.parse(File.read(manifest_path))
    fit_file = FitFile.find_or_create_by!(path: data["fitFile"]) do |f|
      f.name = File.basename(data["fitFile"])
    end

    data["videos"].each do |v|
      video = Video.find_or_create_by!(path: v["path"]) do |vid|
        vid.name = v["name"]
        vid.start_utc = DateTime.parse(v["startUtc"])
        vid.duration_sec = v["durationSec"]
        vid.status = 0 # waiting
      end
      puts "Registered: #{video.name}"
    end
  end
end
