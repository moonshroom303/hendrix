// Test-only software preview of actual egui meshes. Never opens the user's app.
use egui::{self,ColorImage,FullOutput,TextureId};
#[derive(Default)]
pub(super) struct Snapshot { textures:std::collections::HashMap<TextureId,ColorImage> }
impl Snapshot {
 pub fn textures(&mut self,out:&FullOutput){
  for (id,delta) in &out.textures_delta.set {
   let egui::ImageData::Color(data)=&delta.image;
   if let Some([x,y])=delta.pos {
    let old=self.textures.get_mut(id).unwrap();
    for row in 0..data.size[1]{let offset=(y+row)*old.size[0]+x;old.pixels[offset..offset+data.size[0]].copy_from_slice(&data.pixels[row*data.size[0]..(row+1)*data.size[0]]);}
   }else{self.textures.insert(*id,(**data).clone());}
  }
 }
 pub fn save(&self,ctx:&egui::Context,out:FullOutput,size:[u32;2],path:&std::path::Path){
  self.save_region(ctx,out,size,path,None);
 }
 pub fn save_region(&self,ctx:&egui::Context,out:FullOutput,size:[u32;2],path:&std::path::Path,region:Option<egui::Rect>){
  let scale=out.pixels_per_point;
  let mut image=image::RgbaImage::from_pixel(size[0],size[1],image::Rgba([2,5,3,255]));
  for clipped in ctx.tessellate(out.shapes,scale){
   let egui::epaint::Primitive::Mesh(mesh)=clipped.primitive else{continue};
   let Some(texture)=self.textures.get(&mesh.texture_id)else{continue};
   let clip=clipped.clip_rect*scale;
   for triangle in mesh.indices.chunks_exact(3){
    let v=[mesh.vertices[triangle[0] as usize],mesh.vertices[triangle[1] as usize],mesh.vertices[triangle[2] as usize]];
    let pos=v.map(|v|v.pos*scale);
    let edge=|a:egui::Pos2,b:egui::Pos2,p:egui::Pos2|(b.x-a.x)*(p.y-a.y)-(b.y-a.y)*(p.x-a.x);
    let area=edge(pos[0],pos[1],pos[2]);if area.abs()<0.00001{continue}
    let min_x=pos.iter().map(|p|p.x).fold(f32::INFINITY,f32::min).max(clip.left()).max(0.).floor() as u32;
    let min_y=pos.iter().map(|p|p.y).fold(f32::INFINITY,f32::min).max(clip.top()).max(0.).floor() as u32;
    let max_x=pos.iter().map(|p|p.x).fold(f32::NEG_INFINITY,f32::max).min(clip.right()).min(size[0] as f32).ceil() as u32;
    let max_y=pos.iter().map(|p|p.y).fold(f32::NEG_INFINITY,f32::max).min(clip.bottom()).min(size[1] as f32).ceil() as u32;
    for y in min_y..max_y {for x in min_x..max_x {
     let p=egui::pos2(x as f32+0.5,y as f32+0.5);let w=[edge(pos[1],pos[2],p)/area,edge(pos[2],pos[0],p)/area,edge(pos[0],pos[1],p)/area];
     if w.iter().any(|&v|v<0.){continue}
     let uv=v[0].uv.to_vec2()*w[0]+v[1].uv.to_vec2()*w[1]+v[2].uv.to_vec2()*w[2];
     let tx=(uv.x*texture.size[0] as f32).floor().max(0.) as usize;let ty=(uv.y*texture.size[1] as f32).floor().max(0.) as usize;
     let tex=texture.pixels[ty.min(texture.size[1]-1)*texture.size[0]+tx.min(texture.size[0]-1)].to_array();
     let colors=v.map(|v|v.color.to_array());let mut src=[0.;4];
     for i in 0..4{src[i]=(colors[0][i] as f32*w[0]+colors[1][i] as f32*w[1]+colors[2][i] as f32*w[2])*tex[i] as f32/255.;}
     let dst=image.get_pixel_mut(x,y);for i in 0..3{dst[i]=(src[i]+dst[i] as f32*(1.-src[3]/255.)).clamp(0.,255.).round() as u8;}
    }}
   }
  }
  if let Some(r)=region{
   let x=(r.left()*scale).floor().max(0.) as u32;let y=(r.top()*scale).floor().max(0.) as u32;
   let right=(r.right()*scale).ceil().min(size[0] as f32) as u32;let bottom=(r.bottom()*scale).ceil().min(size[1] as f32) as u32;
   image::imageops::crop_imm(&image,x,y,right-x,bottom-y).to_image().save(path).unwrap();
  }else{image.save(path).unwrap();}
 }
}
